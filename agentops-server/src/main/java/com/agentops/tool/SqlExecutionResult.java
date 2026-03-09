package com.agentops.tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SqlExecutionResult {

    private final List<Map<String, Object>> rows;
    private final long latencyMs;
    private final String error;

    private SqlExecutionResult(List<Map<String, Object>> rows, long latencyMs, String error) {
        this.rows = rows;
        this.latencyMs = latencyMs;
        this.error = error;
    }

    public static SqlExecutionResult success(List<Map<String, Object>> rows, long latencyMs) {
        return new SqlExecutionResult(rows, latencyMs, null);
    }

    public static SqlExecutionResult empty(long latencyMs) {
        return new SqlExecutionResult(Collections.emptyList(), latencyMs, null);
    }

    public static SqlExecutionResult error(String error, long latencyMs) {
        return new SqlExecutionResult(Collections.emptyList(), latencyMs, error);
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public int getRowCount() {
        return rows.size();
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getError() {
        return error;
    }

    public boolean hasError() {
        return error != null;
    }

    public boolean isEmpty() {
        return rows.isEmpty() && error == null;
    }

    public boolean isSuccess() {
        return error == null;
    }

    /**
     * 将结果格式化为可读文本（供 Agent 阅读）
     */
    public String toFormattedText() {
        if (hasError()) {
            return "查询失败: " + error;
        }
        if (isEmpty()) {
            return "查询成功，但结果为空（0 行）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("查询成功，返回 ").append(rows.size()).append(" 行，耗时 ")
                .append(latencyMs).append("ms\n\n");

        // 表头
        List<String> columns = List.copyOf(rows.get(0).keySet());
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
        sb.append("| ").append(columns.stream().map(c -> "---").reduce((a, b) -> a + " | " + b).orElse("")).append(" |\n");

        // 数据行（最多展示前 20 行避免过长）
        int displayLimit = Math.min(rows.size(), 20);
        for (int i = 0; i < displayLimit; i++) {
            Map<String, Object> row = rows.get(i);
            sb.append("| ");
            for (String col : columns) {
                Object val = row.get(col);
                sb.append(val == null ? "NULL" : truncate(val.toString(), 50));
                sb.append(" | ");
            }
            sb.append("\n");
        }

        if (rows.size() > displayLimit) {
            sb.append("... 省略剩余 ").append(rows.size() - displayLimit).append(" 行\n");
        }

        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
