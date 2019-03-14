/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.engine.tsfiledata;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.Processor;
import org.apache.iotdb.db.engine.bufferwrite.Action;
import org.apache.iotdb.db.engine.bufferwrite.RestorableTsFileIOWriter;
import org.apache.iotdb.db.engine.filenode.FileNodeManager;
import org.apache.iotdb.db.engine.memcontrol.BasicMemController;
import org.apache.iotdb.db.engine.memtable.IMemTable;
import org.apache.iotdb.db.engine.memtable.MemSeriesLazyMerger;
import org.apache.iotdb.db.engine.memtable.MemTableFlushUtil;
import org.apache.iotdb.db.engine.memtable.PrimitiveMemTable;
import org.apache.iotdb.db.engine.pool.FlushManager;
import org.apache.iotdb.db.engine.querycontext.ReadOnlyMemChunk;
import org.apache.iotdb.db.engine.version.VersionController;
import org.apache.iotdb.db.exception.BufferWriteProcessorException;
import org.apache.iotdb.db.exception.ProcessorException;
import org.apache.iotdb.db.qp.constant.DatetimeUtils;
import org.apache.iotdb.db.utils.ImmediateFuture;
import org.apache.iotdb.db.utils.MemUtils;
import org.apache.iotdb.db.writelog.manager.MultiFileLogNodeManager;
import org.apache.iotdb.db.writelog.node.WriteLogNode;
import org.apache.iotdb.tsfile.common.conf.TSFileDescriptor;
import org.apache.iotdb.tsfile.file.metadata.ChunkMetaData;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.utils.Pair;
import org.apache.iotdb.tsfile.write.record.TSRecord;
import org.apache.iotdb.tsfile.write.record.datapoint.DataPoint;
import org.apache.iotdb.tsfile.write.schema.FileSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TsFileProcessor extends Processor {


  private static final Logger LOGGER = LoggerFactory.getLogger(TsFileProcessor.class);

  private FileSchema fileSchema;
  private volatile Future<Boolean> flushFuture = new ImmediateFuture<>(true);
  private ReentrantLock flushQueryLock = new ReentrantLock();
  private AtomicLong memSize = new AtomicLong();
  private long memThreshold = TSFileDescriptor.getInstance().getConfig().groupSizeInByte;


  //lastFlushTime time unit: nanosecond
  private long lastFlushTime = -1;
  private long valueCount = 0;


  private IMemTable workMemTable;
  private IMemTable flushMemTable;
  private RestorableTsFileIOWriter writer;
  private Action beforeFlushAction;
  private Action afterCloseAction;
  private Action afterFlushAction;
  private String baseDir;
  private File insertFile;
  private List<TsFileResource> tsFileResources;

  private WriteLogNode logNode;
  private VersionController versionController;


  /**
   * constructor of BufferWriteProcessor.
   * data will be stored in baseDir/processorName/ folder.
   *
   * @param baseDir base dir
   * @param processorName processor name
   * @param fileName tsfile name
   * @param fileSchema file schema
   * @throws BufferWriteProcessorException BufferWriteProcessorException
   */
  public TsFileProcessor(String baseDir, String processorName, String fileName,
      Action beforeFlushAction, Action afterFlushAction, Action afterCloseAction, VersionController versionController,
      FileSchema fileSchema) throws BufferWriteProcessorException {
    super(processorName);
    this.fileSchema = fileSchema;
    this.baseDir = baseDir;
    this.processorName = processorName;

    File dataDir = new File(baseDir, processorName);
    if (!dataDir.exists()) {
      if (!dataDir.mkdirs()) {
        throw new BufferWriteProcessorException(
            String.format("Can not create TsFileProcess related folder: %s", dataDir));
      }
      LOGGER.debug("The bufferwrite processor data dir doesn't exists, create new directory {}.",
          dataDir.getAbsolutePath());
    }
    this.insertFile = new File(dataDir, fileName);
    try {
      writer = new RestorableTsFileIOWriter(processorName, insertFile.getAbsolutePath());
    } catch (IOException e) {
      throw new BufferWriteProcessorException(e);
    }

    this.beforeFlushAction = beforeFlushAction;
    this.afterCloseAction = afterCloseAction;
    this.afterFlushAction = afterFlushAction;
    workMemTable = new PrimitiveMemTable();

    if (IoTDBDescriptor.getInstance().getConfig().isEnableWal()) {
      try {
        logNode = MultiFileLogNodeManager.getInstance().getNode(
            processorName + IoTDBConstant.BUFFERWRITE_LOG_NODE_SUFFIX,
            writer.getRestoreFilePath(),
            FileNodeManager.getInstance().getRestoreFilePath(processorName));
      } catch (IOException e) {
        throw new BufferWriteProcessorException(e);
      }
    }
    this.versionController = versionController;
  }

  /**
   * write one data point to the buffer write.
   *
   * @param deviceId device name
   * @param measurementId sensor name
   * @param timestamp timestamp of the data point
   * @param dataType the data type of the value
   * @param value data point value
   * @return true -the size of tsfile or metadata reaches to the threshold. false -otherwise
   * @throws BufferWriteProcessorException if a flushing operation occurs and failed.
   */
  public boolean insert(String deviceId, String measurementId, long timestamp, TSDataType dataType,
      String value)
      throws BufferWriteProcessorException {
    TSRecord record = new TSRecord(timestamp, deviceId);
    DataPoint dataPoint = DataPoint.getDataPoint(dataType, measurementId, value);
    record.addTuple(dataPoint);
    return insert(record);
  }

  /**
   * wrete a ts record into the memtable. If the memory usage is beyond the memThreshold, an async
   * flushing operation will be called.
   *
   * @param tsRecord data to be written
   * @return FIXME what is the mean about the return value??
   * @throws BufferWriteProcessorException if a flushing operation occurs and failed.
   */
  public boolean insert(TSRecord tsRecord) throws BufferWriteProcessorException {
    long memUsage = MemUtils.getRecordSize(tsRecord);
    BasicMemController.UsageLevel level = BasicMemController.getInstance()
        .reportUse(this, memUsage);
    for (DataPoint dataPoint : tsRecord.dataPointList) {
      workMemTable.write(tsRecord.deviceId, dataPoint.getMeasurementId(), dataPoint.getType(),
          tsRecord.time, dataPoint.getValue());
    }
    valueCount++;
    String memory;
    switch (level) {
      case SAFE:
        checkMemThreshold4Flush(memUsage);
        return true;
      case WARNING:
        memory = MemUtils.bytesCntToStr(BasicMemController.getInstance().getTotalUsage());
        LOGGER.warn("Memory usage will exceed warning threshold, current : {}.", memory);
        checkMemThreshold4Flush(memUsage);
        return true;
      case DANGEROUS:
      default:
        memory = MemUtils.bytesCntToStr(BasicMemController.getInstance().getTotalUsage());
        LOGGER.warn("Memory usage will exceed dangerous threshold, current : {}.", memory);
        return false;
    }
  }


  /**
   * Delete data whose timestamp <= 'timestamp' and belonging to timeseries deviceId.measurementId.
   * Delete data in both working MemTable and flushing MemTable.
   *
   * @param deviceId the deviceId of the timeseries to be deleted.
   * @param measurementId the measurementId of the timeseries to be deleted.
   * @param timestamp the upper-bound of deletion time.
   */
  public void delete(String deviceId, String measurementId, long timestamp) {
    workMemTable.delete(deviceId, measurementId, timestamp);
    if (isFlush()) {
      // flushing MemTable cannot be directly modified since another thread is reading it
      flushMemTable = flushMemTable.copy();
      flushMemTable.delete(deviceId, measurementId, timestamp);
    }
  }


  private void checkMemThreshold4Flush(long addedMemory) throws BufferWriteProcessorException {
    long newMem = memSize.addAndGet(addedMemory);
    if (newMem > memThreshold) {
      String usageMem = MemUtils.bytesCntToStr(newMem);
      String threshold = MemUtils.bytesCntToStr(memThreshold);
      LOGGER.info("The usage of memory {} in bufferwrite processor {} reaches the threshold {}",
          usageMem, processorName, threshold);
      try {
        flush();
      } catch (IOException e) {
        LOGGER.error("Flush bufferwrite error.", e);
        throw new BufferWriteProcessorException(e);
      }
    }
  }



  // keyword synchronized is added in this method, so that only one flush task can be submitted now.
  @Override
  public synchronized Future<Boolean> flush() throws IOException {
    // statistic information for flush
    if (lastFlushTime > 0) {
      if (LOGGER.isInfoEnabled()) {
        long thisFlushTime = System.currentTimeMillis();
        LOGGER.info(
            "The bufferwrite processor {}: last flush time is {}, this flush time is {}, "
                + "flush time interval is {}s", getProcessorName(),
            DatetimeUtils.convertMillsecondToZonedDateTime(lastFlushTime / 1000),
            DatetimeUtils.convertMillsecondToZonedDateTime(thisFlushTime),
            (thisFlushTime - lastFlushTime / 1000) / 1000);
      }
    }
    lastFlushTime = System.nanoTime();
    // check value count
    if (valueCount > 0) {
      // waiting for the end of last flush operation.
      try {
        flushFuture.get();
      } catch (InterruptedException | ExecutionException e) {
        LOGGER.error("Encounter an interrupt error when waitting for the flushing, "
                + "the bufferwrite processor is {}.",
            getProcessorName(), e);
        Thread.currentThread().interrupt();
      }
      // update the lastUpdatetime, prepare for flush
      try {
        beforeFlushAction.act();
      } catch (Exception e) {
        LOGGER.error("Failed to flush bufferwrite row group when calling the action function.");
        throw new IOException(e);
      }
      if (IoTDBDescriptor.getInstance().getConfig().isEnableWal()) {
        logNode.notifyStartFlush();
      }
      valueCount = 0;
      switchWorkToFlush();
      long version = versionController.nextVersion();
      BasicMemController.getInstance().reportFree(this, memSize.get());
      memSize.set(0);
      // switch
      flushFuture = FlushManager.getInstance().submit(() -> flushTask("asynchronously",
          version));
    } else {
      flushFuture = new ImmediateFuture<>(true);
    }
    return flushFuture;
  }

  /**
   * the caller mast guarantee no other concurrent caller entering this function.
   *
   * @param displayMessage message that will appear in system log.
   * @param version the operation version that will tagged on the to be flushed memtable
   * (i.e., ChunkGroup)
   * @return true if successfully.
   */
  private boolean flushTask(String displayMessage, long version) {
    boolean result;
    long flushStartTime = System.currentTimeMillis();
    LOGGER.info("The bufferwrite processor {} starts flushing {}.", getProcessorName(),
        displayMessage);
    try {
      if (flushMemTable != null && !flushMemTable.isEmpty()) {
        // flush data
        MemTableFlushUtil.flushMemTable(fileSchema, writer, flushMemTable,
            version);
        // write restore information
        writer.flush();
      }

      afterFlushAction.act();
      if (IoTDBDescriptor.getInstance().getConfig().isEnableWal()) {
        logNode.notifyEndFlush(null);
      }
      result = true;
    } catch (Exception e) {
      LOGGER.error(
          "The bufferwrite processor {} failed to flush {}, when calling the filenodeFlushAction.",
          getProcessorName(), displayMessage, e);
      result = false;
    } finally {
      switchFlushToWork();
      LOGGER.info("The bufferwrite processor {} ends flushing {}.", getProcessorName(),
          displayMessage);
    }
    if (LOGGER.isInfoEnabled()) {
      long flushEndTime = System.currentTimeMillis();
      LOGGER.info(
          "The bufferwrite processor {} flush {}, start time is {}, flush end time is {}, "
              + "flush time consumption is {}ms",
          getProcessorName(), displayMessage,
          DatetimeUtils.convertMillsecondToZonedDateTime(flushStartTime),
          DatetimeUtils.convertMillsecondToZonedDateTime(flushEndTime),
          flushEndTime - flushStartTime);
    }
    return result;
  }

  private void switchWorkToFlush() {
    flushQueryLock.lock();
    try {
      if (flushMemTable == null) {
        flushMemTable = workMemTable;
        workMemTable = new PrimitiveMemTable();
      }
    } finally {
      flushQueryLock.unlock();
    }
  }

  private void switchFlushToWork() {
    flushQueryLock.lock();
    try {
      flushMemTable.clear();
      flushMemTable = null;
      writer.appendMetadata();
    } finally {
      flushQueryLock.unlock();
    }
  }

  @Override
  public boolean canBeClosed() {
    return true;
  }

  @Override
  public void close() throws BufferWriteProcessorException {
    try {
      long closeStartTime = System.currentTimeMillis();
      // flush data and wait for finishing flush
      flush().get();
      // end file
      writer.endFile(fileSchema);
      // update the IntervalFile for interval list
      afterCloseAction.act();
      // flush the changed information for filenode
      afterFlushAction.act();
      //TODO add the tsfile resource.
      TsFileResource resource = new TsFileResource(insertFile, true);
      tsFileResources.add(resource);

      // delete the restore for this bufferwrite processor
      if (LOGGER.isInfoEnabled()) {
        long closeEndTime = System.currentTimeMillis();
        LOGGER.info(
            "Close bufferwrite processor {}, the file name is {}, start time is {}, end time is {}, "
                + "time consumption is {}ms",
            getProcessorName(), insertFile.getAbsolutePath(),
            DatetimeUtils.convertMillsecondToZonedDateTime(closeStartTime),
            DatetimeUtils.convertMillsecondToZonedDateTime(closeEndTime),
            closeEndTime - closeStartTime);
      }
    } catch (IOException e) {
      LOGGER.error("Close the bufferwrite processor error, the bufferwrite is {}.",
          getProcessorName(), e);
      throw new BufferWriteProcessorException(e);
    } catch (Exception e) {
      LOGGER
          .error("Failed to close the bufferwrite processor when calling the action function.", e);
      throw new BufferWriteProcessorException(e);
    }
  }

  @Override
  public long memoryUsage() {
    return 0;
  }

  /**
   * check if is flushing.
   *
   * @return True if flushing
   */
  public boolean isFlush() {
    // starting a flush task has two steps: set the flushMemtable, and then set the flushFuture
    // So, the following case exists: flushMemtable != null but flushFuture is done (because the
    // flushFuture refers to the last finished flush.
    // And, the following case exists,too: flushMemtable == null, but flushFuture is not done.
    // (flushTask() is not finished, but switchToWork() has done)
    // So, checking flushMemTable is more meaningful than flushFuture.isDone().
    return  flushMemTable != null;
  }

  /**
   * get the one (or two) chunk(s) in the memtable ( and the other one in flushing status and then
   * compact them into one TimeValuePairSorter). Then get its (or their) ChunkMetadata(s).
   *
   * @param deviceId device id
   * @param measurementId sensor id
   * @param dataType data type
   * @return corresponding chunk data and chunk metadata in memory
   */
  public Pair<ReadOnlyMemChunk, List<ChunkMetaData>> queryBufferWriteData(String deviceId,
      String measurementId, TSDataType dataType, Map<String, String> props) {
    flushQueryLock.lock();
    try {
      MemSeriesLazyMerger memSeriesLazyMerger = new MemSeriesLazyMerger();
      if (flushMemTable != null) {
        memSeriesLazyMerger.addMemSeries(flushMemTable.query(deviceId, measurementId, dataType, props));
      }
      memSeriesLazyMerger.addMemSeries(workMemTable.query(deviceId, measurementId, dataType, props));
      // memSeriesLazyMerger has handled the props,
      // so we do not need to handle it again in the following readOnlyMemChunk
      ReadOnlyMemChunk timeValuePairSorter = new ReadOnlyMemChunk(dataType, memSeriesLazyMerger,
          Collections.emptyMap());
      return new Pair<>(timeValuePairSorter,
          writer.getMetadatas(deviceId, measurementId, dataType));
    } finally {
      flushQueryLock.unlock();
    }
  }



  public String getBaseDir() {
    return baseDir;
  }


  public String getInsertFilePath() {
    return insertFile.getAbsolutePath();
  }

  public WriteLogNode getLogNode() {
    return logNode;
  }

  /**
   * used for test. We can know when the flush() is called.
   * @return the last flush() time. Time unit: nanosecond.
   */
  public long getLastFlushTime() {
    return lastFlushTime;
  }

  /**
   * used for test. We can block to wait for finishing flushing.
   * @return the future of the flush() task.
   */
  public Future<Boolean> getFlushFuture() {
    return flushFuture;
  }
}
