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
package org.apache.iotdb.cluster.query.coordinatornode.reader;

import com.alipay.sofa.jraft.entity.PeerId;
import java.io.IOException;
import org.apache.iotdb.cluster.query.PathType;
import org.apache.iotdb.db.query.reader.IBatchReader;
import org.apache.iotdb.tsfile.read.common.BatchData;

public class ClusterRpcBatchDataReader implements IBatchReader {

  /**
   * Remote query node
   */
  private PeerId peerId;

  /**
   * Job id in remote query node
   */
  private long jobId;

  /**
   * Path type
   */
  private PathType type;

  /**
   * Batch data
   */
  private BatchData batchData;

  public ClusterRpcBatchDataReader(PeerId peerId, long jobId,
      PathType type, BatchData batchData) {
    this.peerId = peerId;
    this.jobId = jobId;
    this.type = type;
    this.batchData = batchData;
  }

  @Override
  public boolean hasNext() throws IOException {
    return false;
  }

  @Override
  public BatchData nextBatch() throws IOException {
    return null;
  }

  @Override
  public void close() throws IOException {

  }
}
