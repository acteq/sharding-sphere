/**
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.rdb.sharding.jdbc;

import com.dangdang.ddframe.rdb.sharding.executor.StatementExecutor;
import com.dangdang.ddframe.rdb.sharding.executor.wrapper.StatementExecutorWrapper;
import com.dangdang.ddframe.rdb.sharding.jdbc.adapter.AbstractStatementAdapter;
import com.dangdang.ddframe.rdb.sharding.merger.ResultSetFactory;
import com.dangdang.ddframe.rdb.sharding.parser.result.merger.MergeContext;
import com.dangdang.ddframe.rdb.sharding.router.SQLExecutionUnit;
import com.dangdang.ddframe.rdb.sharding.router.SQLRouteResult;
import com.google.common.base.Charsets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支持分片的静态语句对象.
 * 
 * @author gaohongtao
 * @author caohao
 */
public class ShardingStatement extends AbstractStatementAdapter {
    
    @Getter(AccessLevel.PROTECTED)
    private final ShardingConnection shardingConnection;
    
    @Getter
    private final int resultSetType;
    
    @Getter
    private final int resultSetConcurrency;
    
    @Getter
    private final int resultSetHoldability;
    
    private final Map<HashCode, Statement> cachedRoutedStatements = new HashMap<>();
    
    @Getter(AccessLevel.PROTECTED)
    @Setter(AccessLevel.PROTECTED)
    private MergeContext mergeContext;
    
    @Getter(AccessLevel.PROTECTED)
    @Setter(AccessLevel.PROTECTED)
    private ResultSet currentResultSet;
    
    public ShardingStatement(final ShardingConnection shardingConnection) {
        this(shardingConnection, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    public ShardingStatement(final ShardingConnection shardingConnection, final int resultSetType, final int resultSetConcurrency) {
        this(shardingConnection, resultSetType, resultSetConcurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    public ShardingStatement(final ShardingConnection shardingConnection, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability) {
        super(Statement.class);
        this.shardingConnection = shardingConnection;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.resultSetHoldability = resultSetHoldability;
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        return shardingConnection;
    }
    
    @Override
    public ResultSet executeQuery(final String sql) throws SQLException {
        if (null != currentResultSet && !currentResultSet.isClosed()) {
            currentResultSet.close();
        }
        currentResultSet = ResultSetFactory.getResultSet(generateExecutor(sql).executeQuery(), mergeContext);
        return currentResultSet;
    }
    
    @Override
    public int executeUpdate(final String sql) throws SQLException {
        return generateExecutor(sql).executeUpdate();
    }
    
    @Override
    public int executeUpdate(final String sql, final int autoGeneratedKeys) throws SQLException {
        return generateExecutor(sql).executeUpdate(autoGeneratedKeys);
    }
    
    @Override
    public int executeUpdate(final String sql, final int[] columnIndexes) throws SQLException {
        return generateExecutor(sql).executeUpdate(columnIndexes);
    }
    
    @Override
    public int executeUpdate(final String sql, final String[] columnNames) throws SQLException {
        return generateExecutor(sql).executeUpdate(columnNames);
    }
    
    @Override
    public boolean execute(final String sql) throws SQLException {
        return generateExecutor(sql).execute();
    }
    
    @Override
    public boolean execute(final String sql, final int autoGeneratedKeys) throws SQLException {
        return generateExecutor(sql).execute(autoGeneratedKeys);
    }
    
    @Override
    public boolean execute(final String sql, final int[] columnIndexes) throws SQLException {
        return generateExecutor(sql).execute(columnIndexes);
    }
    
    @Override
    public boolean execute(final String sql, final String[] columnNames) throws SQLException {
        return generateExecutor(sql).execute(columnNames);
    }
    
    private StatementExecutor generateExecutor(final String sql) throws SQLException {
        StatementExecutor result = new StatementExecutor(shardingConnection.getShardingContext().getExecutorEngine());
        SQLRouteResult sqlRouteResult = shardingConnection.getShardingContext().getSqlRouteEngine().route(sql, Collections.emptyList());
        mergeContext = sqlRouteResult.getMergeContext();
        mergeContext.setExecutorEngine(shardingConnection.getShardingContext().getExecutorEngine());
        for (SQLExecutionUnit each : sqlRouteResult.getExecutionUnits()) {
            result.addStatement(new StatementExecutorWrapper(generateStatement(each.getSql(), each.getDataSource()), each));
        }
        return result;
    }
    
    private Statement generateStatement(final String sql, final String dataSourceName) throws SQLException {
        HashCode hashCode =  Hashing.md5().newHasher().putString(sql, Charsets.UTF_8).putString(dataSourceName, Charsets.UTF_8).hash();
        if (cachedRoutedStatements.containsKey(hashCode)) {
            return cachedRoutedStatements.get(hashCode);
        }
        Connection connection = shardingConnection.getConnection(dataSourceName);
        Statement result;
        if (0 == resultSetHoldability) {
            result = connection.createStatement(resultSetType, resultSetConcurrency);
        } else {
            result = connection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
        }
        replayMethodsInvocation(result);
        cachedRoutedStatements.put(hashCode, result);
        return result;
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        if (null != currentResultSet) {
            return currentResultSet;
        }
        List<ResultSet> resultSets = new ArrayList<>(getRoutedStatements().size());
        for (Statement each : getRoutedStatements()) {
            resultSets.add(each.getResultSet());
        }
        currentResultSet = ResultSetFactory.getResultSet(resultSets, mergeContext);
        return currentResultSet;
    }
    
    @Override
    public Collection<? extends Statement> getRoutedStatements() throws SQLException {
        return cachedRoutedStatements.values();
    }
    
    @Override
    public void clearRoutedStatements() throws SQLException {
        cachedRoutedStatements.clear();
    }
}
