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
package org.apache.iotdb.cluster.query.manager.coordinatornode;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.iotdb.cluster.config.ClusterConfig;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.db.qp.physical.crud.QueryPlan;

/**
 * Manage all query in cluster
 */
public class ClusterRpcQueryManager{

  /**
   * Key is job id, value is task id.
   */
  private static final ConcurrentHashMap<Long, String> JOB_ID_MAP_TASK_ID = new ConcurrentHashMap<>();

  /**
   * Key is task id, value is manager of a client query.
   */
  private static final ConcurrentHashMap<String, ClusterRpcSingleQueryManager> SINGLE_QUERY_MANAGER_MAP = new ConcurrentHashMap<>();

  /**
   * Assign every query a work id
   */
  private static final AtomicLong TASK_ID = new AtomicLong(0);

  private static final ClusterConfig CLUSTER_CONFIG = ClusterDescriptor.getInstance().getConfig();

  private static final String LOCAL_ADDR = String.format("%s:%d", CLUSTER_CONFIG.getIp(), CLUSTER_CONFIG.getPort());

  /**
   * Add a query
   */
  public void addSingleQuery(long jobId, QueryPlan physicalPlan){
    String taskId = getAndIncreaTaskId();
    JOB_ID_MAP_TASK_ID.put(jobId, taskId);
    SINGLE_QUERY_MANAGER_MAP.put(taskId, new ClusterRpcSingleQueryManager(taskId, physicalPlan));
  }

  /**
   * Get full task id (local address + task id) and increase task id
   */
  private String getAndIncreaTaskId() {
    return String.format("%s:%d", LOCAL_ADDR, TASK_ID.getAndIncrement());
  }

  /**
   * Get query manager by group id
   */
  public ClusterRpcSingleQueryManager getSingleQuery(long jobId) {
    return SINGLE_QUERY_MANAGER_MAP.get(jobId);
  }

  public void releaseQueryResource(long jobId){
    if(SINGLE_QUERY_MANAGER_MAP.containsKey(jobId)){
     SINGLE_QUERY_MANAGER_MAP.remove(jobId).releaseQueryResource();
    }
  }

  private ClusterRpcQueryManager(){
  }

  public static final ClusterRpcQueryManager getInstance() {
    return ClusterRpcQueryManagerHolder.INSTANCE;
  }

  private static class ClusterRpcQueryManagerHolder {

    private static final ClusterRpcQueryManager INSTANCE = new ClusterRpcQueryManager();

    private ClusterRpcQueryManagerHolder() {

    }
  }

}