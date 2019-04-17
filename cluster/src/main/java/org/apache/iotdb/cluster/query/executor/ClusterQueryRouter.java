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
package org.apache.iotdb.cluster.query.executor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.iotdb.cluster.exception.RaftConnectionException;
import org.apache.iotdb.cluster.query.manager.coordinatornode.ClusterRpcQueryManager;
import org.apache.iotdb.cluster.query.manager.coordinatornode.ClusterRpcSingleQueryManager;
import org.apache.iotdb.cluster.query.manager.coordinatornode.ClusterRpcSingleQueryManager.QueryType;
import org.apache.iotdb.db.exception.FileNodeManagerException;
import org.apache.iotdb.db.exception.PathErrorException;
import org.apache.iotdb.db.exception.ProcessorException;
import org.apache.iotdb.db.query.context.QueryContext;
import org.apache.iotdb.db.query.executor.IEngineQueryRouter;
import org.apache.iotdb.db.query.fill.IFill;
import org.apache.iotdb.tsfile.exception.filter.QueryFilterOptimizationException;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.expression.ExpressionType;
import org.apache.iotdb.tsfile.read.expression.IExpression;
import org.apache.iotdb.tsfile.read.expression.QueryExpression;
import org.apache.iotdb.tsfile.read.expression.util.ExpressionOptimizer;
import org.apache.iotdb.tsfile.read.query.dataset.QueryDataSet;
import org.apache.iotdb.tsfile.utils.Pair;

public class ClusterQueryRouter implements IEngineQueryRouter {

  private ThreadLocal<Integer> readDataConsistencyLevel = new ThreadLocal<>();

  @Override
  public QueryDataSet query(QueryExpression queryExpression, QueryContext context)
      throws FileNodeManagerException, PathErrorException {

    ClusterRpcSingleQueryManager queryManager = ClusterRpcQueryManager.getInstance()
        .getSingleQuery(context.getJobId());
    try {
      if (queryExpression.hasQueryFilter()) {

        IExpression optimizedExpression = ExpressionOptimizer.getInstance()
            .optimize(queryExpression.getExpression(), queryExpression.getSelectedSeries());
        queryExpression.setExpression(optimizedExpression);

        if (optimizedExpression.getType() == ExpressionType.GLOBAL_TIME) {
          queryManager.init(QueryType.GLOBAL_TIME, getReadDataConsistencyLevel());
          ClusterExecutorWithoutTimeGenerator engineExecutor =
              new ClusterExecutorWithoutTimeGenerator(queryExpression, queryManager,
                  getReadDataConsistencyLevel());
          return engineExecutor.executeWithGlobalTimeFilter(context);
        } else {
          queryManager.init(QueryType.FILTER, getReadDataConsistencyLevel());
          ClusterExecutorWithTimeGenerator engineExecutor = new ClusterExecutorWithTimeGenerator(
              queryExpression, queryManager, getReadDataConsistencyLevel());
          return engineExecutor.execute(context);
        }

      } else {
        queryManager.init(QueryType.NO_FILTER, getReadDataConsistencyLevel());
        ClusterExecutorWithoutTimeGenerator engineExecutor =
            new ClusterExecutorWithoutTimeGenerator(queryExpression, queryManager,
                getReadDataConsistencyLevel());
        return engineExecutor.executeWithoutFilter(context);
      }
    } catch (QueryFilterOptimizationException | IOException | RaftConnectionException e) {
      throw new FileNodeManagerException(e);
    }
  }

  @Override
  public QueryDataSet aggregate(List<Path> selectedSeries, List<String> aggres,
      IExpression expression, QueryContext context)
      throws QueryFilterOptimizationException, FileNodeManagerException, IOException, PathErrorException, ProcessorException {
    return null;
  }

  @Override
  public QueryDataSet groupBy(List<Path> selectedSeries, List<String> aggres,
      IExpression expression, long unit, long origin, List<Pair<Long, Long>> intervals,
      QueryContext context)
      throws ProcessorException, QueryFilterOptimizationException, FileNodeManagerException, PathErrorException, IOException {
    return null;
  }

  @Override
  public QueryDataSet fill(List<Path> fillPaths, long queryTime, Map<TSDataType, IFill> fillType,
      QueryContext context) throws FileNodeManagerException, PathErrorException, IOException {
    return null;
  }

  public int getReadDataConsistencyLevel() {
    return readDataConsistencyLevel.get();
  }

  public void setReadDataConsistencyLevel(int readDataConsistencyLevel) {
    this.readDataConsistencyLevel.set(readDataConsistencyLevel);
  }
}