package com.agentops.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志查询工具 — 封装对 service_error_log 表的预定义查询
 *
 * 提供三种分析能力:
 * 1. 高频错误消息聚合（按 error_message 分组统计）
 * 2. 错误时间分布（按分钟聚合，识别错误峰值）
 * 3. trace_id 链路关联（根据 trace_id 查询完整调用链）
 *
 * 所有查询均通过 monitorDataSource 只读连接池执行，
 * 内置 statement_timeout=10s + maxRows=100 安全约束。
 */
@Service
public class LogQueryTool {

    private static final Logger log = LoggerFactory.getLogger(LogQueryTool.class);

    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int MAX_ROWS = 100;

    private final DataSource monitorDataSource;

    public LogQueryTool(@Qualifier("monitorDataSource") DataSource monitorDataSource) {
        this.monitorDataSource = monitorDataSource;
    }

    /**
     * 查询高频错误消息 — 按 error_message 分组统计出现次数
     *
     * @param minutesAgo    查询最近 N 分钟的数据
     * @param serviceName   服务名过滤（null 表示不过滤）
     * @param limit         返回 top N 条
     * @return 查询结果（columns: error_message, error_count, error_level, latest_time, service_name）
     */
    public SqlExecutionResult queryTopErrors(int minutesAgo, String serviceName, int limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT error_message, COUNT(*) AS error_count, ")
           .append("error_level, MAX(created_at) AS latest_time, service_name ")
           .append("FROM service_error_log ")
           .append("WHERE created_at >= NOW() - INTERVAL '").append(minutesAgo).append(" minutes' ");

        if (serviceName != null && !serviceName.isBlank()) {
            sql.append("AND service_name = '").append(escapeSql(serviceName)).append("' ");
        }

        sql.append("GROUP BY error_message, error_level, service_name ")
           .append("ORDER BY error_count DESC ")
           .append("LIMIT ").append(Math.min(limit, MAX_ROWS));

        return executeQuery(sql.toString(), "queryTopErrors");
    }

    /**
     * 查询错误时间分布 — 按分钟聚合，用于识别错误峰值
     *
     * @param minutesAgo  查询最近 N 分钟的数据
     * @param serviceName 服务名过滤（null 表示不过滤）
     * @return 查询结果（columns: time_bucket, error_count, error_services, fatal_count）
     */
    public SqlExecutionResult queryErrorTimeline(int minutesAgo, String serviceName) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT date_trunc('minute', created_at) AS time_bucket, ")
           .append("COUNT(*) AS error_count, ")
           .append("COUNT(DISTINCT service_name) AS error_services, ")
           .append("SUM(CASE WHEN error_level = 'FATAL' THEN 1 ELSE 0 END) AS fatal_count ")
           .append("FROM service_error_log ")
           .append("WHERE created_at >= NOW() - INTERVAL '").append(minutesAgo).append(" minutes' ");

        if (serviceName != null && !serviceName.isBlank()) {
            sql.append("AND service_name = '").append(escapeSql(serviceName)).append("' ");
        }

        sql.append("GROUP BY time_bucket ORDER BY time_bucket LIMIT ").append(MAX_ROWS);

        return executeQuery(sql.toString(), "queryErrorTimeline");
    }

    /**
     * 根据 trace_id 查询关联日志 — 追踪完整调用链
     *
     * @param traceId 链路追踪 ID
     * @return 查询结果（完整日志记录）
     */
    public SqlExecutionResult queryByTraceId(String traceId) {
        String sql = "SELECT service_name, error_level, error_message, request_path, "
                + "request_method, response_code, created_at "
                + "FROM service_error_log "
                + "WHERE trace_id = '" + escapeSql(traceId) + "' "
                + "ORDER BY created_at LIMIT " + MAX_ROWS;

        return executeQuery(sql, "queryByTraceId");
    }

    /**
     * 查询指定服务的错误详情（含堆栈）
     *
     * @param serviceName 服务名
     * @param minutesAgo  最近 N 分钟
     * @param errorLevel  错误级别过滤（null 表示不过滤）
     * @return 查询结果
     */
    public SqlExecutionResult queryErrorDetails(String serviceName, int minutesAgo, String errorLevel) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT service_name, error_level, error_message, ")
           .append("LEFT(stack_trace, 500) AS stack_trace_head, ")
           .append("request_path, response_code, trace_id, created_at ")
           .append("FROM service_error_log ")
           .append("WHERE created_at >= NOW() - INTERVAL '").append(minutesAgo).append(" minutes' ")
           .append("AND service_name = '").append(escapeSql(serviceName)).append("' ");

        if (errorLevel != null && !errorLevel.isBlank()) {
            sql.append("AND error_level = '").append(escapeSql(errorLevel)).append("' ");
        }

        sql.append("ORDER BY created_at DESC LIMIT 50");

        return executeQuery(sql.toString(), "queryErrorDetails");
    }

    /**
     * 统一查询执行入口
     */
    private SqlExecutionResult executeQuery(String sql, String queryName) {
        long startNanos = System.nanoTime();
        log.debug("LogQueryTool.{}: {}", queryName, sql);

        try (Connection conn = monitorDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            stmt.setMaxRows(MAX_ROWS);

            try (ResultSet rs = stmt.executeQuery()) {
                List<Map<String, Object>> rows = resultSetToList(rs);
                long latencyMs = elapsedMs(startNanos);
                log.debug("LogQueryTool.{}: {} 行, {}ms", queryName, rows.size(), latencyMs);

                return rows.isEmpty()
                        ? SqlExecutionResult.empty(latencyMs)
                        : SqlExecutionResult.success(rows, latencyMs);
            }
        } catch (SQLTimeoutException e) {
            long latencyMs = elapsedMs(startNanos);
            return SqlExecutionResult.error("日志查询超时: " + e.getMessage(), latencyMs);
        } catch (SQLException e) {
            long latencyMs = elapsedMs(startNanos);
            return SqlExecutionResult.error("日志查询错误 [" + e.getSQLState() + "]: " + e.getMessage(), latencyMs);
        }
    }

    private List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        String[] colNames = new String[colCount];
        for (int i = 0; i < colCount; i++) {
            colNames[i] = meta.getColumnLabel(i + 1);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < MAX_ROWS) {
            Map<String, Object> row = new LinkedHashMap<>(colCount);
            for (int i = 0; i < colCount; i++) {
                row.put(colNames[i], rs.getObject(i + 1));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 简单 SQL 转义（防止单引号注入）
     */
    private String escapeSql(String input) {
        if (input == null) return "";
        return input.replace("'", "''");
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
