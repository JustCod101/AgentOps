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
 * 指标查询工具 — 封装对 system_metric 表的预定义查询
 *
 * 提供四种分析能力:
 * 1. 指标趋势查询（按分钟聚合的时序数据）
 * 2. 最新指标快照（各指标当前值）
 * 3. 异常检测（超过均值 ± 2σ 的数据点）
 * 4. 时间维度对比（当前 vs 1小时前）
 *
 * 所有查询均通过 monitorDataSource 只读连接池执行。
 */
@Service
public class MetricQueryTool {

    private static final Logger log = LoggerFactory.getLogger(MetricQueryTool.class);

    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int MAX_ROWS = 100;

    private final DataSource monitorDataSource;

    public MetricQueryTool(@Qualifier("monitorDataSource") DataSource monitorDataSource) {
        this.monitorDataSource = monitorDataSource;
    }

    /**
     * 查询指标趋势 — 按分钟聚合的时序数据
     *
     * @param metricName  指标名（cpu_usage / memory_usage / qps / p99_latency / disk_io / db_connections）
     * @param minutesAgo  查询最近 N 分钟
     * @param serviceName 服务名过滤（null 不过滤）
     * @return 查询结果（columns: time_bucket, avg_value, max_value, min_value, metric_unit）
     */
    public SqlExecutionResult queryTrend(String metricName, int minutesAgo, String serviceName) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT date_trunc('minute', created_at) AS time_bucket, ")
           .append("AVG(metric_value) AS avg_value, ")
           .append("MAX(metric_value) AS max_value, ")
           .append("MIN(metric_value) AS min_value, ")
           .append("metric_unit ")
           .append("FROM system_metric ")
           .append("WHERE metric_name = '").append(escapeSql(metricName)).append("' ")
           .append("AND created_at >= NOW() - INTERVAL '").append(minutesAgo).append(" minutes' ");

        if (serviceName != null && !serviceName.isBlank()) {
            sql.append("AND service_name = '").append(escapeSql(serviceName)).append("' ");
        }

        sql.append("GROUP BY time_bucket, metric_unit ORDER BY time_bucket LIMIT ").append(MAX_ROWS);

        return executeQuery(sql.toString(), "queryTrend");
    }

    /**
     * 查询最新指标快照 — 各指标的最新值
     *
     * @param serviceName 服务名过滤（null 不过滤）
     * @return 查询结果（columns: metric_name, metric_value, metric_unit, host, latest_time）
     */
    public SqlExecutionResult queryLatestSnapshot(String serviceName) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT ON (metric_name, host) ")
           .append("metric_name, metric_value, metric_unit, host, service_name, created_at AS latest_time ")
           .append("FROM system_metric ");

        if (serviceName != null && !serviceName.isBlank()) {
            sql.append("WHERE service_name = '").append(escapeSql(serviceName)).append("' ");
        }

        sql.append("ORDER BY metric_name, host, created_at DESC LIMIT ").append(MAX_ROWS);

        return executeQuery(sql.toString(), "queryLatestSnapshot");
    }

    /**
     * 异常检测 — 查找超过均值 ± 2个标准差的数据点
     *
     * 使用窗口函数计算每个指标的 AVG 和 STDDEV，
     * 筛选出 |value - avg| > 2 * stddev 的异常点。
     *
     * @param minutesAgo 查询最近 N 分钟
     * @return 查询结果（columns: metric_name, metric_value, avg_value, stddev_value, deviation, host, created_at）
     */
    public SqlExecutionResult detectAnomalies(int minutesAgo) {
        String sql = "WITH stats AS ("
                + "  SELECT metric_name, host, "
                + "    AVG(metric_value) AS avg_val, "
                + "    STDDEV(metric_value) AS stddev_val "
                + "  FROM system_metric "
                + "  WHERE created_at >= NOW() - INTERVAL '" + minutesAgo + " minutes' "
                + "  GROUP BY metric_name, host "
                + "  HAVING STDDEV(metric_value) > 0"
                + ") "
                + "SELECT m.metric_name, m.metric_value, m.metric_unit, "
                + "  s.avg_val AS avg_value, s.stddev_val AS stddev_value, "
                + "  ROUND(ABS(m.metric_value - s.avg_val) / s.stddev_val, 2) AS deviation_sigma, "
                + "  m.host, m.service_name, m.created_at "
                + "FROM system_metric m "
                + "JOIN stats s ON m.metric_name = s.metric_name AND m.host = s.host "
                + "WHERE m.created_at >= NOW() - INTERVAL '" + minutesAgo + " minutes' "
                + "AND ABS(m.metric_value - s.avg_val) > 2 * s.stddev_val "
                + "ORDER BY deviation_sigma DESC "
                + "LIMIT " + MAX_ROWS;

        return executeQuery(sql, "detectAnomalies");
    }

    /**
     * 时间维度对比 — 当前时段 vs 1小时前同时段
     *
     * 对比最近 N 分钟的均值 与 1小时前同样 N 分钟的均值，
     * 计算变化率（百分比），帮助判断指标是否恶化。
     *
     * @param minutesAgo 对比窗口大小（如 10 表示最近10分钟 vs 1小时前的10分钟）
     * @return 查询结果（columns: metric_name, current_avg, previous_avg, change_pct, metric_unit）
     */
    public SqlExecutionResult compareWithPrevious(int minutesAgo) {
        String sql = "WITH current_period AS ("
                + "  SELECT metric_name, AVG(metric_value) AS current_avg, metric_unit "
                + "  FROM system_metric "
                + "  WHERE created_at >= NOW() - INTERVAL '" + minutesAgo + " minutes' "
                + "  GROUP BY metric_name, metric_unit"
                + "), previous_period AS ("
                + "  SELECT metric_name, AVG(metric_value) AS previous_avg "
                + "  FROM system_metric "
                + "  WHERE created_at >= NOW() - INTERVAL '" + (minutesAgo + 60) + " minutes' "
                + "  AND created_at < NOW() - INTERVAL '60 minutes' "
                + "  GROUP BY metric_name"
                + ") "
                + "SELECT c.metric_name, "
                + "  ROUND(c.current_avg::numeric, 2) AS current_avg, "
                + "  ROUND(p.previous_avg::numeric, 2) AS previous_avg, "
                + "  CASE WHEN p.previous_avg = 0 THEN NULL "
                + "    ELSE ROUND(((c.current_avg - p.previous_avg) / p.previous_avg * 100)::numeric, 2) "
                + "  END AS change_pct, "
                + "  c.metric_unit "
                + "FROM current_period c "
                + "LEFT JOIN previous_period p ON c.metric_name = p.metric_name "
                + "ORDER BY ABS(COALESCE(((c.current_avg - p.previous_avg) / NULLIF(p.previous_avg, 0) * 100), 0)) DESC "
                + "LIMIT " + MAX_ROWS;

        return executeQuery(sql, "compareWithPrevious");
    }

    /**
     * 统一查询执行入口
     */
    private SqlExecutionResult executeQuery(String sql, String queryName) {
        long startNanos = System.nanoTime();
        log.debug("MetricQueryTool.{}: {}", queryName, sql);

        try (Connection conn = monitorDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            stmt.setMaxRows(MAX_ROWS);

            try (ResultSet rs = stmt.executeQuery()) {
                List<Map<String, Object>> rows = resultSetToList(rs);
                long latencyMs = elapsedMs(startNanos);
                log.debug("MetricQueryTool.{}: {} 行, {}ms", queryName, rows.size(), latencyMs);

                return rows.isEmpty()
                        ? SqlExecutionResult.empty(latencyMs)
                        : SqlExecutionResult.success(rows, latencyMs);
            }
        } catch (SQLTimeoutException e) {
            long latencyMs = elapsedMs(startNanos);
            return SqlExecutionResult.error("指标查询超时: " + e.getMessage(), latencyMs);
        } catch (SQLException e) {
            long latencyMs = elapsedMs(startNanos);
            return SqlExecutionResult.error("指标查询错误 [" + e.getSQLState() + "]: " + e.getMessage(), latencyMs);
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

    private String escapeSql(String input) {
        if (input == null) return "";
        return input.replace("'", "''");
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
