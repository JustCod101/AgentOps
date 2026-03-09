package com.agentops.tool;

import com.agentops.domain.entity.SqlAuditLog;
import com.agentops.repository.SqlAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙盒 SQL 执行器
 *
 * 安全隔离措施:
 * 1. 独立只读连接池（monitorDataSource, maxPool=5, 与业务连接池完全隔离）
 * 2. 连接级别 SET default_transaction_read_only = ON + statement_timeout = 10s
 * 3. PreparedStatement + maxRows(100) + fetchSize(50)
 * 4. 结果集大小限制（最多 100 行）
 * 5. 所有执行记录写入 sql_audit_log 审计表
 */
@Service
public class SandboxSqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(SandboxSqlExecutor.class);

    private static final int MAX_ROWS = 100;
    private static final int FETCH_SIZE = 50;
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private final DataSource monitorDataSource;
    private final SqlAuditRepository auditRepo;

    public SandboxSqlExecutor(
            @Qualifier("monitorDataSource") DataSource monitorDataSource,
            SqlAuditRepository auditRepo) {
        this.monitorDataSource = monitorDataSource;
        this.auditRepo = auditRepo;
    }

    /**
     * 在只读沙盒中执行 SQL 查询
     *
     * @param sql       已通过 SqlSanitizer 安全检查的 SQL
     * @param sessionId 诊断会话 ID（用于审计关联）
     * @param timeout   查询超时时间，null 则使用默认 10s
     * @return 查询结果
     */
    public SqlExecutionResult execute(String sql, String sessionId, Duration timeout) {
        long startNanos = System.nanoTime();

        try (Connection conn = monitorDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // 设置查询约束
            int timeoutSeconds = timeout != null
                    ? (int) timeout.toSeconds()
                    : DEFAULT_TIMEOUT_SECONDS;
            stmt.setQueryTimeout(timeoutSeconds);
            stmt.setMaxRows(MAX_ROWS);
            stmt.setFetchSize(FETCH_SIZE);

            log.debug("沙盒执行 SQL [session={}]: {}", sessionId, sql);

            boolean hasResultSet = stmt.execute();
            long latencyMs = elapsedMs(startNanos);

            if (!hasResultSet) {
                // 正常情况不会到这里（只执行 SELECT），但作为防御
                saveAudit(sessionId, sql, true, latencyMs, 0, null);
                return SqlExecutionResult.empty(latencyMs);
            }

            try (ResultSet rs = stmt.getResultSet()) {
                List<Map<String, Object>> rows = resultSetToList(rs);
                latencyMs = elapsedMs(startNanos);

                log.debug("SQL 执行完成 [session={}]: {} 行, {}ms",
                        sessionId, rows.size(), latencyMs);

                saveAudit(sessionId, sql, true, latencyMs, rows.size(), null);
                return rows.isEmpty()
                        ? SqlExecutionResult.empty(latencyMs)
                        : SqlExecutionResult.success(rows, latencyMs);
            }

        } catch (SQLTimeoutException e) {
            long latencyMs = elapsedMs(startNanos);
            String errorMsg = "查询超时: " + e.getMessage();
            log.warn("SQL 执行超时 [session={}]: {}ms, sql={}", sessionId, latencyMs, sql);
            saveAudit(sessionId, sql, true, latencyMs, 0, errorMsg);
            return SqlExecutionResult.error(errorMsg, latencyMs);

        } catch (SQLException e) {
            long latencyMs = elapsedMs(startNanos);
            String errorMsg = "SQL 执行错误 [" + e.getSQLState() + "]: " + e.getMessage();
            log.warn("SQL 执行失败 [session={}]: {}", sessionId, errorMsg);
            saveAudit(sessionId, sql, true, latencyMs, 0, errorMsg);
            return SqlExecutionResult.error(errorMsg, latencyMs);
        }
    }

    /**
     * 使用默认超时执行
     */
    public SqlExecutionResult execute(String sql, String sessionId) {
        return execute(sql, sessionId, null);
    }

    /**
     * ResultSet → List<Map<String, Object>>
     * 保持列的原始顺序（LinkedHashMap），最多读取 MAX_ROWS 行
     */
    private List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        // 预先读取列名（使用 label 以支持别名）
        String[] columnNames = new String[columnCount];
        for (int i = 0; i < columnCount; i++) {
            columnNames[i] = meta.getColumnLabel(i + 1);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < MAX_ROWS) {
            Map<String, Object> row = new LinkedHashMap<>(columnCount);
            for (int i = 0; i < columnCount; i++) {
                row.put(columnNames[i], rs.getObject(i + 1));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 写入审计日志
     */
    private void saveAudit(String sessionId, String sql, boolean executed,
                           long latencyMs, int resultRows, String errorMessage) {
        try {
            SqlAuditLog audit = SqlAuditLog.builder()
                    .sessionId(sessionId)
                    .originalSql(sql)
                    .sanitizedSql(sql)
                    .isAllowed(true)
                    .sqlType("SELECT")
                    .executed(executed)
                    .executionMs((int) latencyMs)
                    .resultRows(resultRows)
                    .errorMessage(errorMessage)
                    .build();
            auditRepo.save(audit);
        } catch (Exception e) {
            // 审计写入失败不应影响主流程
            log.error("审计日志写入失败 [session={}]: {}", sessionId, e.getMessage());
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
