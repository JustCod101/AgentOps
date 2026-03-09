package com.agentops.tool;

import com.agentops.domain.entity.SqlAuditLog;
import com.agentops.repository.SqlAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SandboxSqlExecutorTest {

    @Mock
    private DataSource monitorDataSource;

    @Mock
    private SqlAuditRepository auditRepo;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @Mock
    private ResultSetMetaData resultSetMetaData;

    private SandboxSqlExecutor executor;

    private static final String SESSION_ID = "test-session-001";
    private static final String SIMPLE_SQL = "SELECT * FROM slow_query_log LIMIT 10";

    @BeforeEach
    void setUp() throws SQLException {
        executor = new SandboxSqlExecutor(monitorDataSource, auditRepo);

        // 默认 mock 链: DataSource → Connection → PreparedStatement
        when(monitorDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
    }

    // =====================================================================
    // 正常查询
    // =====================================================================

    @Nested
    @DisplayName("正常查询执行")
    class SuccessfulExecution {

        @Test
        @DisplayName("查询返回多行数据")
        void returnsMultipleRows() throws SQLException {
            // 模拟 3 列 2 行的结果集
            when(preparedStatement.execute()).thenReturn(true);
            when(preparedStatement.getResultSet()).thenReturn(resultSet);
            when(resultSet.getMetaData()).thenReturn(resultSetMetaData);

            when(resultSetMetaData.getColumnCount()).thenReturn(3);
            when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");
            when(resultSetMetaData.getColumnLabel(2)).thenReturn("query_text");
            when(resultSetMetaData.getColumnLabel(3)).thenReturn("execution_time_ms");

            // 2 行数据
            when(resultSet.next()).thenReturn(true, true, false);
            when(resultSet.getObject(1)).thenReturn(1L, 2L);
            when(resultSet.getObject(2)).thenReturn("SELECT 1", "SELECT 2");
            when(resultSet.getObject(3)).thenReturn(150, 3200);

            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            assertTrue(result.isSuccess());
            assertFalse(result.hasError());
            assertFalse(result.isEmpty());
            assertEquals(2, result.getRowCount());

            // 验证第一行数据
            Map<String, Object> row0 = result.getRows().get(0);
            assertEquals(1L, row0.get("id"));
            assertEquals("SELECT 1", row0.get("query_text"));
            assertEquals(150, row0.get("execution_time_ms"));

            // 验证列顺序保持（LinkedHashMap）
            List<String> keys = List.copyOf(row0.keySet());
            assertEquals("id", keys.get(0));
            assertEquals("query_text", keys.get(1));
            assertEquals("execution_time_ms", keys.get(2));
        }

        @Test
        @DisplayName("查询返回空结果集")
        void returnsEmptyResultSet() throws SQLException {
            when(preparedStatement.execute()).thenReturn(true);
            when(preparedStatement.getResultSet()).thenReturn(resultSet);
            when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
            when(resultSetMetaData.getColumnCount()).thenReturn(1);
            when(resultSetMetaData.getColumnLabel(1)).thenReturn("count");
            when(resultSet.next()).thenReturn(false);

            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            assertTrue(result.isSuccess());
            assertTrue(result.isEmpty());
            assertEquals(0, result.getRowCount());
            assertNull(result.getError());
        }

        @Test
        @DisplayName("非 ResultSet 返回（防御性处理）")
        void noResultSet() throws SQLException {
            when(preparedStatement.execute()).thenReturn(false);

            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            assertTrue(result.isSuccess());
            assertTrue(result.isEmpty());
        }
    }

    // =====================================================================
    // PreparedStatement 约束验证
    // =====================================================================

    @Nested
    @DisplayName("PreparedStatement 约束设置")
    class StatementConstraints {

        @Test
        @DisplayName("默认超时 10 秒")
        void defaultTimeout() throws SQLException {
            when(preparedStatement.execute()).thenReturn(false);

            executor.execute(SIMPLE_SQL, SESSION_ID);

            verify(preparedStatement).setQueryTimeout(10);
        }

        @Test
        @DisplayName("自定义超时")
        void customTimeout() throws SQLException {
            when(preparedStatement.execute()).thenReturn(false);

            executor.execute(SIMPLE_SQL, SESSION_ID, Duration.ofSeconds(5));

            verify(preparedStatement).setQueryTimeout(5);
        }

        @Test
        @DisplayName("maxRows 设置为 100")
        void maxRowsSet() throws SQLException {
            when(preparedStatement.execute()).thenReturn(false);

            executor.execute(SIMPLE_SQL, SESSION_ID);

            verify(preparedStatement).setMaxRows(100);
        }

        @Test
        @DisplayName("fetchSize 设置为 50")
        void fetchSizeSet() throws SQLException {
            when(preparedStatement.execute()).thenReturn(false);

            executor.execute(SIMPLE_SQL, SESSION_ID);

            verify(preparedStatement).setFetchSize(50);
        }

        @Test
        @DisplayName("所有约束按顺序设置")
        void allConstraintsSetInOrder() throws SQLException {
            when(preparedStatement.execute()).thenReturn(false);

            executor.execute(SIMPLE_SQL, SESSION_ID, Duration.ofSeconds(8));

            var inOrder = inOrder(preparedStatement);
            inOrder.verify(preparedStatement).setQueryTimeout(8);
            inOrder.verify(preparedStatement).setMaxRows(100);
            inOrder.verify(preparedStatement).setFetchSize(50);
            inOrder.verify(preparedStatement).execute();
        }
    }

    // =====================================================================
    // 异常处理
    // =====================================================================

    @Nested
    @DisplayName("异常处理")
    class ErrorHandling {

        @Test
        @DisplayName("SQL 执行超时返回 error 结果")
        void sqlTimeoutException() throws SQLException {
            when(preparedStatement.execute()).thenThrow(
                    new SQLTimeoutException("Statement cancelled due to timeout"));

            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            assertTrue(result.hasError());
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("超时"));
        }

        @Test
        @DisplayName("SQL 语法错误返回 error 结果")
        void sqlSyntaxException() throws SQLException {
            when(preparedStatement.execute()).thenThrow(
                    new SQLException("relation \"nonexistent\" does not exist", "42P01"));

            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            assertTrue(result.hasError());
            assertTrue(result.getError().contains("42P01"));
            assertTrue(result.getError().contains("nonexistent"));
        }

        @Test
        @DisplayName("连接获取失败返回 error 结果")
        void connectionFailure() throws SQLException {
            when(monitorDataSource.getConnection()).thenThrow(
                    new SQLException("Connection pool exhausted", "08001"));

            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            assertTrue(result.hasError());
            assertTrue(result.getError().contains("Connection pool exhausted"));
        }

        @Test
        @DisplayName("异常时仍记录延迟时间")
        void errorStillRecordsLatency() throws SQLException {
            when(preparedStatement.execute()).thenThrow(new SQLException("error", "XX000"));

            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            assertTrue(result.getLatencyMs() >= 0);
        }
    }

    // =====================================================================
    // 审计日志
    // =====================================================================

    @Nested
    @DisplayName("审计日志记录")
    class AuditLogging {

        @Test
        @DisplayName("成功执行写入审计日志")
        void auditOnSuccess() throws SQLException {
            when(preparedStatement.execute()).thenReturn(true);
            when(preparedStatement.getResultSet()).thenReturn(resultSet);
            when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
            when(resultSetMetaData.getColumnCount()).thenReturn(1);
            when(resultSetMetaData.getColumnLabel(1)).thenReturn("cnt");
            when(resultSet.next()).thenReturn(true, false);
            when(resultSet.getObject(1)).thenReturn(42);

            executor.execute(SIMPLE_SQL, SESSION_ID);

            ArgumentCaptor<SqlAuditLog> captor = ArgumentCaptor.forClass(SqlAuditLog.class);
            verify(auditRepo).save(captor.capture());

            SqlAuditLog audit = captor.getValue();
            assertEquals(SESSION_ID, audit.getSessionId());
            assertEquals(SIMPLE_SQL, audit.getOriginalSql());
            assertTrue(audit.getIsAllowed());
            assertEquals("SELECT", audit.getSqlType());
            assertTrue(audit.getExecuted());
            assertEquals(1, audit.getResultRows());
            assertNull(audit.getErrorMessage());
            assertNotNull(audit.getExecutionMs());
            assertTrue(audit.getExecutionMs() >= 0);
        }

        @Test
        @DisplayName("失败执行也写入审计日志（含错误信息）")
        void auditOnFailure() throws SQLException {
            when(preparedStatement.execute()).thenThrow(
                    new SQLException("permission denied", "42501"));

            executor.execute(SIMPLE_SQL, SESSION_ID);

            ArgumentCaptor<SqlAuditLog> captor = ArgumentCaptor.forClass(SqlAuditLog.class);
            verify(auditRepo).save(captor.capture());

            SqlAuditLog audit = captor.getValue();
            assertEquals(SESSION_ID, audit.getSessionId());
            assertTrue(audit.getExecuted());
            assertEquals(0, audit.getResultRows());
            assertNotNull(audit.getErrorMessage());
            assertTrue(audit.getErrorMessage().contains("permission denied"));
        }

        @Test
        @DisplayName("审计日志写入失败不影响主流程")
        void auditFailureDoesNotAffectMainFlow() throws SQLException {
            when(preparedStatement.execute()).thenReturn(false);
            doThrow(new RuntimeException("DB write failed")).when(auditRepo).save(any());

            // 不应抛异常
            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("空结果集审计记录 resultRows=0")
        void auditEmptyResult() throws SQLException {
            when(preparedStatement.execute()).thenReturn(true);
            when(preparedStatement.getResultSet()).thenReturn(resultSet);
            when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
            when(resultSetMetaData.getColumnCount()).thenReturn(1);
            when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");
            when(resultSet.next()).thenReturn(false);

            executor.execute(SIMPLE_SQL, SESSION_ID);

            ArgumentCaptor<SqlAuditLog> captor = ArgumentCaptor.forClass(SqlAuditLog.class);
            verify(auditRepo).save(captor.capture());
            assertEquals(0, captor.getValue().getResultRows());
        }
    }

    // =====================================================================
    // 资源管理
    // =====================================================================

    @Nested
    @DisplayName("资源管理")
    class ResourceManagement {

        @Test
        @DisplayName("正常执行后 Connection 和 Statement 被关闭")
        void resourcesClosedOnSuccess() throws SQLException {
            when(preparedStatement.execute()).thenReturn(false);

            executor.execute(SIMPLE_SQL, SESSION_ID);

            verify(preparedStatement).close();
            verify(connection).close();
        }

        @Test
        @DisplayName("异常时 Connection 和 Statement 也被关闭")
        void resourcesClosedOnError() throws SQLException {
            when(preparedStatement.execute()).thenThrow(new SQLException("error"));

            executor.execute(SIMPLE_SQL, SESSION_ID);

            verify(preparedStatement).close();
            verify(connection).close();
        }

        @Test
        @DisplayName("ResultSet 在使用后被关闭")
        void resultSetClosed() throws SQLException {
            when(preparedStatement.execute()).thenReturn(true);
            when(preparedStatement.getResultSet()).thenReturn(resultSet);
            when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
            when(resultSetMetaData.getColumnCount()).thenReturn(1);
            when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");
            when(resultSet.next()).thenReturn(false);

            executor.execute(SIMPLE_SQL, SESSION_ID);

            verify(resultSet).close();
        }
    }

    // =====================================================================
    // SqlExecutionResult 功能
    // =====================================================================

    @Nested
    @DisplayName("SqlExecutionResult")
    class ResultTests {

        @Test
        @DisplayName("toFormattedText 格式化成功结果")
        void formattedTextSuccess() throws SQLException {
            when(preparedStatement.execute()).thenReturn(true);
            when(preparedStatement.getResultSet()).thenReturn(resultSet);
            when(resultSet.getMetaData()).thenReturn(resultSetMetaData);

            when(resultSetMetaData.getColumnCount()).thenReturn(2);
            when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");
            when(resultSetMetaData.getColumnLabel(2)).thenReturn("name");

            when(resultSet.next()).thenReturn(true, false);
            when(resultSet.getObject(1)).thenReturn(1);
            when(resultSet.getObject(2)).thenReturn("test");

            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            String text = result.toFormattedText();
            assertTrue(text.contains("1 行"));
            assertTrue(text.contains("id"));
            assertTrue(text.contains("name"));
            assertTrue(text.contains("test"));
        }

        @Test
        @DisplayName("toFormattedText 格式化错误结果")
        void formattedTextError() throws SQLException {
            when(preparedStatement.execute()).thenThrow(new SQLException("table not found"));

            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            String text = result.toFormattedText();
            assertTrue(text.contains("查询失败"));
        }

        @Test
        @DisplayName("toFormattedText 格式化空结果")
        void formattedTextEmpty() throws SQLException {
            when(preparedStatement.execute()).thenReturn(true);
            when(preparedStatement.getResultSet()).thenReturn(resultSet);
            when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
            when(resultSetMetaData.getColumnCount()).thenReturn(1);
            when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");
            when(resultSet.next()).thenReturn(false);

            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            String text = result.toFormattedText();
            assertTrue(text.contains("结果为空"));
        }

        @Test
        @DisplayName("NULL 值显示为 NULL 文本")
        void nullValueFormatting() throws SQLException {
            when(preparedStatement.execute()).thenReturn(true);
            when(preparedStatement.getResultSet()).thenReturn(resultSet);
            when(resultSet.getMetaData()).thenReturn(resultSetMetaData);

            when(resultSetMetaData.getColumnCount()).thenReturn(1);
            when(resultSetMetaData.getColumnLabel(1)).thenReturn("stack_trace");

            when(resultSet.next()).thenReturn(true, false);
            when(resultSet.getObject(1)).thenReturn(null);

            SqlExecutionResult result = executor.execute(SIMPLE_SQL, SESSION_ID);

            assertEquals(1, result.getRowCount());
            assertNull(result.getRows().get(0).get("stack_trace"));

            String text = result.toFormattedText();
            assertTrue(text.contains("NULL"));
        }
    }
}
