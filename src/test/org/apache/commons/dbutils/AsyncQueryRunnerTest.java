/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.dbutils;

import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;

import javax.sql.DataSource;

import org.apache.commons.dbutils.handlers.ArrayHandler;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AsyncQueryRunnerTest {
    AsyncQueryRunner runner;
	ArrayHandler handler;
    
    @Mock DataSource dataSource;
    @Mock Connection conn;
    @Mock PreparedStatement stmt;
    @Mock ParameterMetaData meta;
    @Mock ResultSet results;
    
    static final Method getParameterCount, getParameterType, getParameterMetaData;
    static {
        try {
            getParameterCount = ParameterMetaData.class.getMethod("getParameterCount", new Class[0]);
            getParameterType = ParameterMetaData.class.getMethod("getParameterType", new Class[]{int.class});
            getParameterMetaData = PreparedStatement.class.getMethod("getParameterMetaData", new Class[0]);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Before
    public void setUp() throws Exception {
		MockitoAnnotations.initMocks(this);	// init the mocks

		when(dataSource.getConnection()).thenReturn(conn);
		when(conn.prepareStatement(any(String.class))).thenReturn(stmt);
		when(stmt.getParameterMetaData()).thenReturn(meta);
		when(stmt.getResultSet()).thenReturn(results);
		when(stmt.executeQuery()).thenReturn(results);
		when(results.next()).thenReturn(false);

		 handler = new ArrayHandler();
		 runner = new AsyncQueryRunner(dataSource);
    }
    
    //
    // Batch test cases
    //
    
    private void callGoodBatch(Connection conn, Object[][] params) throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	RunnableFuture<int[]> future = runner.batch(conn, "select * from blah where ? = ?", params);
    	verify(stmt, times(2)).addBatch();
    	verify(stmt, never()).close();	// make sure the statement is still open

    	future.run();
    	verify(stmt, times(1)).executeBatch();
    	verify(stmt, times(1)).close();	// make sure we closed the statement
    	verify(conn, times(0)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown
    }
    
    private void callGoodBatch(Object[][] params) throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	RunnableFuture<int[]> future = runner.batch("select * from blah where ? = ?", params);
    	verify(stmt, times(2)).addBatch();
    	verify(stmt, never()).close();	// make sure the statement is still open

    	future.run();
    	verify(stmt, times(1)).executeBatch();
    	verify(stmt, times(1)).close();	// make sure we closed the statement
    	verify(conn, times(1)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown
    }

    @Test
    public void testGoodBatch() throws Exception {
    	String[][] params = new String[][] { { "unit", "unit" }, { "test", "test" } };
    	
    	callGoodBatch(params);
    }
    
    @Test
    public void testGoodBatchPmdTrue() throws Exception {
    	runner = new AsyncQueryRunner(dataSource, true);
    	String[][] params = new String[][] { { "unit", "unit" }, { "test", "test" } };
    	
    	callGoodBatch(params);
    }
    
    @Test
    public void testGoodBatchDefaultConstructor() throws Exception {
    	runner = new AsyncQueryRunner();
    	String[][] params = new String[][] { { "unit", "unit" }, { "test", "test" } };
    	
    	callGoodBatch(conn, params);
    }

    @Test
    public void testNullParamsBatch() throws Exception {
    	String[][] params = new String[][] { { null, "unit" }, { "test", null } };
    	
    	callGoodBatch(params);
    }
    


    // helper method for calling batch when an exception is expected
    private void callBatchWithException(String sql, Object[][] params) throws Exception {
    	RunnableFuture<int[]> future = null;
    	boolean caught = false;
    	
    	try {
    		future = runner.batch(sql, params);
        	verify(stmt, times(2)).addBatch();
        	verify(stmt, never()).close();	// make sure the statement is still open

        	future.run();
        	verify(stmt, times(1)).executeBatch();
        	verify(stmt, times(1)).close();	// make sure the statement is closed
        	verify(conn, times(1)).close();	// make sure the connection is closed
        	
        	future.get();
    	} catch(SQLException e) {
    		caught = true;
    	} catch(ExecutionException e) {
    		caught = true;
    	}
    	
    	if(!caught)
    		fail("Exception never thrown, but expected");
    }

    @Test
    public void testTooFewParamsBatch() throws Exception {
    	String[][] params = new String[][] { { "unit" }, { "test" } };
    	
    	callBatchWithException("select * from blah where ? = ?", params);
    }

    @Test
    public void testTooManyParamsBatch() throws Exception {
    	String[][] params = new String[][] { { "unit", "unit", "unit" }, { "test", "test", "test" } };
    	
    	callBatchWithException("select * from blah where ? = ?", params);
    }

    @Test(expected=SQLException.class)
    public void testNullConnectionBatch() throws Exception {
    	String[][] params = new String[][] { { "unit", "unit" }, { "test", "test" } };
    	
    	when(meta.getParameterCount()).thenReturn(2);
    	when(dataSource.getConnection()).thenReturn(null);
    	
    	runner.batch("select * from blah where ? = ?", params);
    }

    @Test(expected=SQLException.class)
    public void testNullSqlBatch() throws Exception {
    	String[][] params = new String[][] { { "unit", "unit" }, { "test", "test" } };
    	
    	when(meta.getParameterCount()).thenReturn(2);
    	
    	runner.batch(null, params);
    }

    @Test(expected=SQLException.class)
    public void testNullParamsArgBatch() throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	
    	runner.batch("select * from blah where ? = ?", null);
    }

    @Test
    public void testAddBatchException() throws Exception {
    	String[][] params = new String[][] { { "unit", "unit" }, { "test", "test" } };
    	
    	doThrow(new SQLException()).when(stmt).addBatch();
    	
    	callBatchWithException("select * from blah where ? = ?", params);
    }

    @Test
    public void testExecuteBatchException() throws Exception {
    	String[][] params = new String[][] { { "unit", "unit" }, { "test", "test" } };
    	
    	doThrow(new SQLException()).when(stmt).executeBatch();
    	
    	callBatchWithException("select * from blah where ? = ?", params);
    }

    
    //
    // Query test cases
    //
    private void callGoodQuery(Connection conn) throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	RunnableFuture<Object[]> future = runner.query(conn, "select * from blah where ? = ?", handler, "unit", "test");
    	verify(stmt, never()).close();	// make sure the statement is still open

    	future.run();
    	verify(stmt, times(1)).executeQuery();
    	verify(results, times(1)).close();
    	verify(stmt, times(1)).close();	// make sure we closed the statement
    	verify(conn, times(0)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown
    	
    	// call the other variation of query
    	when(meta.getParameterCount()).thenReturn(0);
    	future = runner.query(conn, "select * from blah", handler);
    	verify(stmt, times(1)).close();	// make sure the statement has only been closed once

    	future.run();
    	verify(stmt, times(2)).executeQuery();
    	verify(results, times(2)).close();
    	verify(stmt, times(2)).close();	// make sure we closed the statement
    	verify(conn, times(0)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown
    	
    }
    
    private void callGoodQuery() throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	RunnableFuture<Object[]> future = runner.query("select * from blah where ? = ?", handler, "unit", "test");
    	verify(stmt, never()).close();	// make sure the statement is still open

    	future.run();
    	verify(stmt, times(1)).executeQuery();
    	verify(results, times(1)).close();
    	verify(stmt, times(1)).close();	// make sure we closed the statement
    	verify(conn, times(1)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown

    	// call the other variation of query
    	when(meta.getParameterCount()).thenReturn(0);
    	future = runner.query("select * from blah", handler);
    	verify(stmt, times(1)).close();	// make sure the statement is still open

    	future.run();
    	verify(stmt, times(2)).executeQuery();
    	verify(results, times(2)).close();
    	verify(stmt, times(2)).close();	// make sure we closed the statement
    	verify(conn, times(2)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown
    }
    
    @Test
    public void testGoodQuery() throws Exception {
    	callGoodQuery();
    }
    
    @Test
    public void testGoodQueryPmdTrue() throws Exception {
    	runner = new AsyncQueryRunner(true);
    	callGoodQuery(conn);
    }
    
    @Test
    public void testGoodQueryDefaultConstructor() throws Exception {
    	runner = new AsyncQueryRunner();
    	callGoodQuery(conn);
    }
    

    // helper method for calling batch when an exception is expected
    private void callQueryWithException(Object... params) throws Exception {
    	RunnableFuture<Object[]> future = null;
    	boolean caught = false;
    	
    	try {
        	when(meta.getParameterCount()).thenReturn(2);
    		future = runner.query("select * from blah where ? = ?", handler, params);
        	verify(stmt, never()).close();	// make sure the statement is still open

        	future.run();
        	verify(stmt, times(1)).executeQuery();
        	verify(results, times(1)).close();
        	verify(stmt, times(1)).close();	// make sure we closed the statement
        	verify(conn, times(1)).close();	// make sure we closed the connection
        	
        	future.get();
    	} catch(SQLException e) {
    		caught = true;
    	} catch(ExecutionException e) {
    		caught = true;
    	}
    	
    	if(!caught)
    		fail("Exception never thrown, but expected");
    }

    @Test
    public void testNoParamsQuery() throws Exception {
    	callQueryWithException();
    }
    
    @Test
    public void testTooFewParamsQuery() throws Exception {
    	callQueryWithException("unit");
    }

    @Test
    public void testTooManyParamsQuery() throws Exception {
    	callQueryWithException("unit", "test", "fail");
    }

    @Test(expected=SQLException.class)
    public void testNullConnectionQuery() throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	when(dataSource.getConnection()).thenReturn(null);
    	
    	runner.query("select * from blah where ? = ?", handler, "unit", "test");
    }

    @Test(expected=SQLException.class)
    public void testNullSqlQuery() throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	
    	runner.query(null, handler);
    }

    @Test(expected=SQLException.class)
    public void testNullHandlerQuery() throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	
    	runner.query("select * from blah where ? = ?", null);
    }

    @Test
    public void testExecuteQueryException() throws Exception {
    	doThrow(new SQLException()).when(stmt).executeQuery();
    	
    	callQueryWithException(handler, "unit", "test");
    }


    //
    // Update test cases
    //
    private void callGoodUpdate(Connection conn) throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	RunnableFuture<Integer> future = runner.update(conn, "update blah set ? = ?", "unit", "test");
    	verify(stmt, never()).close();	// make sure the statement is still open

    	future.run();
    	verify(stmt, times(1)).executeUpdate();
    	verify(stmt, times(1)).close();	// make sure we closed the statement
    	verify(conn, times(0)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown
    	
    	// call the other variation
    	when(meta.getParameterCount()).thenReturn(0);
    	future = runner.update(conn, "update blah set unit = test");
    	verify(stmt, times(1)).close();	// make sure the statement is still open

    	future.run();
    	verify(stmt, times(2)).executeUpdate();
    	verify(stmt, times(2)).close();	// make sure we closed the statement
    	verify(conn, times(0)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown

    	// call the other variation
    	when(meta.getParameterCount()).thenReturn(1);
    	future = runner.update(conn, "update blah set unit = ?", "test");
    	verify(stmt, times(2)).close();	// make sure the statement is still open

    	future.run();
    	verify(stmt, times(3)).executeUpdate();
    	verify(stmt, times(3)).close();	// make sure we closed the statement
    	verify(conn, times(0)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown
    }
    
    private void callGoodUpdate() throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	RunnableFuture<Integer> future = runner.update("update blah set ? = ?", "unit", "test");
    	verify(stmt, never()).close();	// make sure the statement is still open

    	future.run();
    	verify(stmt, times(1)).executeUpdate();
    	verify(stmt, times(1)).close();	// make sure we closed the statement
    	verify(conn, times(1)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown

    	// call the other variation
    	when(meta.getParameterCount()).thenReturn(0);
    	future = runner.update("update blah set unit = test");
    	verify(stmt, times(1)).close();	// make sure the statement is still open

    	future.run();
    	verify(stmt, times(2)).executeUpdate();
    	verify(stmt, times(2)).close();	// make sure we closed the statement
    	verify(conn, times(2)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown

    	// call the other variation
    	when(meta.getParameterCount()).thenReturn(1);
    	future = runner.update("update blah set unit = ?", "test");
    	verify(stmt, times(2)).close();	// make sure the statement is still open

    	future.run();
    	verify(stmt, times(3)).executeUpdate();
    	verify(stmt, times(3)).close();	// make sure we closed the statement
    	verify(conn, times(3)).close();	// make sure we closed the connection

    	future.get(); // make sure an exception wasn't thrown
    }
    
    @Test
    public void testGoodUpdate() throws Exception {
    	callGoodUpdate();
    }
    
    @Test
    public void testGoodUpdatePmdTrue() throws Exception {
    	runner = new AsyncQueryRunner(true);
    	callGoodUpdate(conn);
    }

    @Test
    public void testGoodUpdateDefaultConstructor() throws Exception {
    	runner = new AsyncQueryRunner();
    	callGoodUpdate(conn);
    }

    // helper method for calling batch when an exception is expected
    private void callUpdateWithException(Object... params) throws Exception {
    	RunnableFuture<Integer> future = null;
    	boolean caught = false;
    	
    	try {
    		when(meta.getParameterCount()).thenReturn(2);
    		future = runner.update("select * from blah where ? = ?", params);
        	verify(stmt, never()).close();	// make sure the statement is still open

        	future.run();
        	verify(stmt, times(1)).executeUpdate();
        	verify(stmt, times(1)).close();	// make sure we closed the statement
        	verify(conn, times(1)).close();	// make sure we closed the connection

        	future.get();
    	} catch(SQLException e) {
    		caught = true;
    	} catch(ExecutionException e) {
    		caught = true;
    	}
    	
    	if(!caught)
    		fail("Exception never thrown, but expected");
    }

    @Test
    public void testNoParamsUpdate() throws Exception {
    	callUpdateWithException();
    }

    @Test
    public void testTooFewParamsUpdate() throws Exception {
    	callUpdateWithException("unit");
    }

    @Test
    public void testTooManyParamsUpdate() throws Exception {
    	callUpdateWithException("unit", "test", "fail");
    }

    @Test(expected=SQLException.class)
    public void testNullConnectionUpdate() throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	when(dataSource.getConnection()).thenReturn(null);
    	
    	runner.update("select * from blah where ? = ?", "unit", "test");
    }

    @Test(expected=SQLException.class)
    public void testNullSqlUpdate() throws Exception {
    	when(meta.getParameterCount()).thenReturn(2);
    	
    	runner.update(null);
    }

    @Test
    public void testExecuteUpdateException() throws Exception {
    	doThrow(new SQLException()).when(stmt).executeUpdate();
    	
    	callUpdateWithException("unit", "test");
    }
    
    //
    // Random tests
    //
    class MyBean {
    	private int a;
    	private double b;
    	private String c;
    	
		public int getA() {	return a; }
		public void setA(int a) { this.a = a; }
		public double getB() { return b; }
		public void setB(double b) { this.b = b; }
		public String getC() { return c; }
		public void setC(String c) { this.c = c; }
    }
    
    @Test
    public void testFillStatementWithBean() throws Exception {
    	MyBean bean = new MyBean();
    	when(meta.getParameterCount()).thenReturn(3);
    	runner.fillStatementWithBean(stmt, bean, new String[] { "a", "b", "c" });
    }
    
    @Test(expected=NullPointerException.class)
    public void testFillStatementWithBeanNullNames() throws Exception {
    	MyBean bean = new MyBean();
    	when(meta.getParameterCount()).thenReturn(3);
    	runner.fillStatementWithBean(stmt, bean, new String[] { "a", "b", null });
    }
    
    @Test(expected=SQLException.class)
    public void testBadPrepareConnection() throws Exception {
    	runner = new AsyncQueryRunner();
    	runner.update("update blah set unit = test");
    }
}