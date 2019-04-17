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
package org.apache.iotdb.cluster.query.reader;

import com.alipay.sofa.jraft.entity.PeerId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.apache.iotdb.cluster.query.PathType;
import org.apache.iotdb.db.query.reader.IBatchReader;
import org.apache.iotdb.tsfile.read.common.BatchData;

public class ClusterRpcBatchDataReader implements IBatchReader {

  /**
   * Remote query node
   */
  private PeerId peerId;

  /**
   * Task id in remote query node
   */
  private String taskId;

  /**
   * Path type
   */
  private PathType type;

  /**
   * Current batch data
   */
  private BatchData currentBatchData;

  /**
   * Batch data
   */
  private LinkedList<BatchData> batchDataList;

  private boolean remoteDataFinish;

  public ClusterRpcBatchDataReader(PeerId peerId, String taskId,
      PathType type, BatchData batchData) {
    this.peerId = peerId;
    this.taskId = taskId;
    this.type = type;
    this.batchDataList = new LinkedList<>();
    this.batchDataList.add(batchData);
    this.remoteDataFinish = false;
  }

  @Override
  public boolean hasNext() throws IOException {
    if(currentBatchData == null || !currentBatchData.hasNext()){
      updateCurrentBatchData();
    }
    return false;
  }

  private void updateCurrentBatchData(){
    if(!batchDataList.isEmpty()){
      currentBatchData = batchDataList.remove(0);
    }
  }

  @Override
  public BatchData nextBatch() throws IOException {
    return null;
  }

  @Override
  public void close() throws IOException {

  }

  public boolean isRemoteDataFinish() {
    return remoteDataFinish;
  }

  public void setRemoteDataFinish(boolean remoteDataFinish) {
    this.remoteDataFinish = remoteDataFinish;
  }
}