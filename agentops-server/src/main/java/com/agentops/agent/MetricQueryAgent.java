package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import com.agentops.statemachine.ReflectionEngine;
import com.agentops.statemachine.ReflectionEngine.ReflectionResult;
import com.agentops.tool.MetricQueryTool;
import com.agentops.tool.SqlExecutionResult;
import com.agentops.trace.TraceStore;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MetricQuery Agent — 系统指标查询与分析专家
 *
 * 核心能力:
 * 1. CPU / 内存 / QPS / P99延迟 趋势查询（按分钟聚合）
 * 2. 异常检测: 超过均值 ± 2σ 的数据点自动标记
 * 3. 时间维度对比: 当前 vs 1小时前，计算变化率
 *
 * 执行流程 (Act-Observe-Reflect):
 * ┌─────────┐     ┌─────────────────┐     ┌───────────┐     ┌───────────┐
 * │ THOUGHT │────▶│      ACT        │────▶│  OBSERVE  │────▶│  REFLECT  │
 * │ 理解任务 │     │ 趋势+异常+对比查询│     │ 聚合分析结果│     │ 判断是否补查│
 * └─────────┘     └─────────────────┘     └───────────┘     └─────┬─────┘
 *                       ▲                                         │
 *                       │            补充查询（调整范围）             │
 *                       └─────────────────────────────────────────┘
 */
@Service
public class MetricQueryAgent {

    private static final Logger log = LoggerFactory.getLogger(MetricQueryAgent.class);

    private static final String AGENT_NAME = "METRIC_QUERY";
    private static final int MAX_ATTEMPTS = 3;

    /**
     * 可查询的指标名列表
     */
    private static final List<String> ALL_METRICS = List.of(
            "cpu_usage", "memory_usage", "qps", "p99_latency", "disk_io", "db_connections"
    );

    /**
     * 指标分析 Prompt — 让 LLM 解读多维指标数据，识别异常模式
     */
    private static final String ANALYSIS_PROMPT_TEMPLATE = """
            你是一个系统运维指标分析专家。请根据以下查询结果，分析系统健康状况。

            ## 用户问题
            %s

            ## 分析任务
            %s

            ## 查询结果

            ### 关键指标趋势（按分钟聚合）
            %s

            ### 异常数据点（超过均值 ± 2σ）
            %s

            ### 时间维度对比（当前 vs 1小时前）
            %s

            ## 请输出以下分析（Markdown 格式）:
            1. **指标概览**: 各核心指标当前水位（正常/偏高/危险）
            2. **异常指标**: 哪些指标出现了统计学意义上的异常，偏离均值多少个σ
            3. **趋势变化**: 与1小时前相比变化最大的指标，变化率是多少
            4. **相关性分析**: 异常指标之间是否存在关联（如 QPS 上升 → P99 上升 → CPU 上升）
            5. **结论**: 一句话概括系统当前最突出的指标异常
            """;

    /**
     * 任务解析 Prompt
     */
    private static final String TASK_PARSE_PROMPT = """
            从以下任务描述中提取查询参数。严格按格式输出，每行一个参数。

            任务: %s
            用户问题: %s

            可选指标: cpu_usage, memory_usage, qps, p99_latency, disk_io, db_connections

            请输出（如果无法确定则使用默认值）:
            MINUTES=<查询最近多少分钟的数据，默认30>
            METRICS=<需要查询的指标，逗号分隔，如 cpu_usage,qps。默认 ALL>
            SERVICE=<服务名过滤，未指定则输出 ALL>
            """;

    private final ChatLanguageModel workerModel;
    private final MetricQueryTool metricQueryTool;
    private final ReflectionEngine reflectionEngine;
    private final TraceStore traceStore;

    public MetricQueryAgent(@Qualifier("workerModel") ChatLanguageModel workerModel,
                             MetricQueryTool metricQueryTool,
                             ReflectionEngine reflectionEngine,
                             TraceStore traceStore) {
        this.workerModel = workerModel;
        this.metricQueryTool = metricQueryTool;
        this.reflectionEngine = reflectionEngine;
        this.traceStore = traceStore;
    }

    /**
     * 执行指标查询分析任务（Act-Observe-Reflect 循环）
     *
     * @param task          子任务描述（如"查询最近30分钟的 CPU 和 QPS 趋势"）
     * @param sessionId     诊断会话 ID
     * @param originalQuery 用户原始问题
     * @return Agent 执行结果
     */
    public AgentResult execute(String task, String sessionId, String originalQuery) {
        log.info("MetricQueryAgent 开始执行 [session={}]: task={}", sessionId, task);

        // ======================== THOUGHT ========================
        String thought = String.format(
                "收到任务: %s\n用户问题: %s\n"
                + "计划: 1.解析目标指标 → 2.查询趋势数据 → 3.异常检测 → 4.时间对比 → 5.LLM 综合分析",
                task, originalQuery);
        traceStore.recordThought(sessionId, AGENT_NAME, thought);

        List<AttemptRecord> attemptHistory = new ArrayList<>();

        // ======================== ACT-OBSERVE-REFLECT LOOP ========================
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            log.info("MetricQueryAgent 第 {} 次尝试 [session={}]", attempt, sessionId);

            // ---------- ACT Step 1: 解析查询参数 ----------
            QueryParams params = parseTaskParams(task, originalQuery, attemptHistory, sessionId);
            traceStore.recordAction(sessionId, AGENT_NAME, "PARSE_TASK_PARAMS",
                    Map.of("task", task, "attempt", attempt),
                    params.toString(), true);

            // ---------- ACT Step 2: 查询各指标趋势 ----------
            StringBuilder trendData = new StringBuilder();
            boolean hasAnyData = false;
            boolean hasError = false;
            String firstError = null;

            for (String metric : params.metrics) {
                SqlExecutionResult trend = metricQueryTool.queryTrend(
                        metric, params.minutesAgo, params.serviceName);

                traceStore.recordAction(sessionId, AGENT_NAME, "QUERY_TREND_" + metric.toUpperCase(),
                        Map.of("metric", metric, "minutesAgo", params.minutesAgo),
                        trend.toFormattedText(), trend.isSuccess());

                if (trend.hasError()) {
                    hasError = true;
                    if (firstError == null) firstError = trend.getError();
                } else if (!trend.isEmpty()) {
                    hasAnyData = true;
                    trendData.append("#### ").append(metric).append("\n")
                             .append(trend.toFormattedText()).append("\n\n");
                }
            }

            // ---------- ACT Step 3: 异常检测 ----------
            SqlExecutionResult anomalies = metricQueryTool.detectAnomalies(params.minutesAgo);
            traceStore.recordAction(sessionId, AGENT_NAME, "DETECT_ANOMALIES",
                    Map.of("minutesAgo", params.minutesAgo),
                    anomalies.toFormattedText(), anomalies.isSuccess());

            if (!anomalies.isEmpty()) hasAnyData = true;
            if (anomalies.hasError() && firstError == null) firstError = anomalies.getError();

            // ---------- ACT Step 4: 时间维度对比 ----------
            SqlExecutionResult comparison = metricQueryTool.compareWithPrevious(params.minutesAgo);
            traceStore.recordAction(sessionId, AGENT_NAME, "COMPARE_WITH_PREVIOUS",
                    Map.of("minutesAgo", params.minutesAgo),
                    comparison.toFormattedText(), comparison.isSuccess());

            if (!comparison.isEmpty()) hasAnyData = true;
            if (comparison.hasError() && firstError == null) firstError = comparison.getError();

            // ---------- OBSERVE: 检查结果 ----------

            // 有查询出错
            if (hasError && !hasAnyData) {
                String observation = String.format(
                        "指标查询出错（第 %d 次）: %s", attempt, firstError);
                traceStore.recordObservation(sessionId, AGENT_NAME, observation);
                attemptHistory.add(new AttemptRecord(attempt, params.toString(), "查询出错: " + firstError));

                if (attempt < MAX_ATTEMPTS) {
                    ReflectionResult reflection = reflectionEngine.reflect(
                            task, originalQuery, "MetricQueryTool 预定义查询",
                            firstError, formatAttemptHistory(attemptHistory));
                    traceStore.recordReflection(sessionId, AGENT_NAME, reflection.toPromptText());
                    if (!reflection.shouldRetry()) {
                        return AgentResult.failure(AGENT_NAME, "指标查询失败: " + firstError);
                    }
                }
                continue;
            }

            // 所有结果为空
            if (!hasAnyData) {
                String observation = String.format(
                        "所有指标查询结果为空（第 %d 次），参数: %s", attempt, params);
                traceStore.recordObservation(sessionId, AGENT_NAME, observation);
                attemptHistory.add(new AttemptRecord(attempt, params.toString(), "结果全部为空"));

                if (attempt < MAX_ATTEMPTS) {
                    ReflectionResult reflection = reflectionEngine.reflect(
                            task, originalQuery, "查询参数: " + params,
                            "所有指标查询结果为空，可能时间范围不对或指标名不匹配",
                            formatAttemptHistory(attemptHistory));
                    traceStore.recordReflection(sessionId, AGENT_NAME, reflection.toPromptText());
                    if (!reflection.shouldRetry()) {
                        return AgentResult.success(AGENT_NAME,
                                "查询时间段内无指标数据",
                                "未查到指标数据，可能指标采集未启动或时间范围不匹配");
                    }
                }
                continue;
            }

            // ---------- ACT Step 5: LLM 综合分析 ----------
            String trendText = trendData.isEmpty() ? "无趋势数据" : trendData.toString();
            String analysisResult = analyzeResults(task, originalQuery,
                    trendText, anomalies, comparison, sessionId);
            traceStore.recordAction(sessionId, AGENT_NAME, "LLM_ANALYSIS",
                    Map.of("attempt", attempt),
                    analysisResult, true);

            // ---------- OBSERVE: 分析完成 ----------
            String successObs = String.format(
                    "指标分析完成（第 %d 次）: %d 个指标有数据，%d 个异常点",
                    attempt, params.metrics.size(), anomalies.getRowCount());
            traceStore.recordObservation(sessionId, AGENT_NAME, successObs);
            traceStore.recordDecision(sessionId, AGENT_NAME,
                    "指标分析完成，已识别异常指标和变化趋势");

            log.info("MetricQueryAgent 执行成功 [session={}]: 第 {} 次尝试", sessionId, attempt);

            // 构建数据摘要
            StringBuilder dataSummary = new StringBuilder();
            dataSummary.append("## 指标趋势\n").append(trendText).append("\n");
            if (!anomalies.isEmpty()) {
                dataSummary.append("## 异常数据点（>2σ）\n").append(anomalies.toFormattedText()).append("\n\n");
            }
            if (!comparison.isEmpty()) {
                dataSummary.append("## 时间对比（当前 vs 1小时前）\n").append(comparison.toFormattedText()).append("\n\n");
            }
            dataSummary.append("## AI 分析结论\n").append(analysisResult);

            return AgentResult.success(AGENT_NAME, dataSummary.toString(),
                    String.format("分析了最近 %d 分钟的 %d 个指标，发现 %d 个异常数据点",
                            params.minutesAgo, params.metrics.size(), anomalies.getRowCount()));
        }

        // ======================== 所有重试用尽 ========================
        log.warn("MetricQueryAgent 所有重试用尽 [session={}]", sessionId);
        traceStore.recordDecision(sessionId, AGENT_NAME,
                "已尝试 " + MAX_ATTEMPTS + " 次，均未获得有效结果");

        AttemptRecord lastAttempt = attemptHistory.get(attemptHistory.size() - 1);
        return AgentResult.failure(AGENT_NAME,
                String.format("经过 %d 次尝试均未成功。最后一次: %s", MAX_ATTEMPTS, lastAttempt.result));
    }

    /**
     * 解析任务参数
     */
    private QueryParams parseTaskParams(String task, String originalQuery,
                                         List<AttemptRecord> history, String sessionId) {
        if (!history.isEmpty()) {
            AttemptRecord last = history.get(history.size() - 1);
            if (last.result.contains("为空")) {
                QueryParams expanded = parseFromLlm(task, originalQuery, sessionId);
                return new QueryParams(
                        Math.min(expanded.minutesAgo * 2, 1440),
                        ALL_METRICS, // 扩大到所有指标
                        null
                );
            }
        }
        return parseFromLlm(task, originalQuery, sessionId);
    }

    private QueryParams parseFromLlm(String task, String originalQuery, String sessionId) {
        try {
            String prompt = String.format(TASK_PARSE_PROMPT, task, originalQuery);
            String response = workerModel.generate(prompt);

            int minutes = extractIntParam(response, "MINUTES", 30);
            List<String> metrics = extractMetricsParam(response);
            String service = extractStringParam(response, "SERVICE");

            return new QueryParams(minutes, metrics, service);
        } catch (Exception e) {
            log.warn("LLM 解析任务参数失败 [session={}]: {}，使用默认值", sessionId, e.getMessage());
            return new QueryParams(30, ALL_METRICS, null);
        }
    }

    /**
     * 调用 LLM 分析指标结果
     */
    private String analyzeResults(String task, String originalQuery,
                                   String trendText,
                                   SqlExecutionResult anomalies,
                                   SqlExecutionResult comparison,
                                   String sessionId) {
        String prompt = String.format(ANALYSIS_PROMPT_TEMPLATE,
                originalQuery, task,
                trendText,
                anomalies.isEmpty() ? "未检测到异常数据点（所有指标在 2σ 范围内）" : anomalies.toFormattedText(),
                comparison.isEmpty() ? "无对比数据" : comparison.toFormattedText());

        try {
            return workerModel.generate(prompt);
        } catch (Exception e) {
            log.error("LLM 分析调用失败 [session={}]: {}", sessionId, e.getMessage());
            return "LLM 分析失败: " + e.getMessage() + "\n请人工查看上方原始指标数据。";
        }
    }

    // ======================== 参数解析辅助方法 ========================

    private int extractIntParam(String text, String paramName, int defaultValue) {
        for (String line : text.split("\n")) {
            if (line.strip().startsWith(paramName + "=")) {
                String value = line.strip().substring(paramName.length() + 1).strip();
                try {
                    return Integer.parseInt(value.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException ignored) {}
            }
        }
        return defaultValue;
    }

    private String extractStringParam(String text, String paramName) {
        for (String line : text.split("\n")) {
            if (line.strip().startsWith(paramName + "=")) {
                String value = line.strip().substring(paramName.length() + 1).strip();
                if ("ALL".equalsIgnoreCase(value) || value.isBlank()) return null;
                return value;
            }
        }
        return null;
    }

    /**
     * 从 LLM 输出中提取指标列表
     */
    private List<String> extractMetricsParam(String text) {
        for (String line : text.split("\n")) {
            if (line.strip().startsWith("METRICS=")) {
                String value = line.strip().substring("METRICS=".length()).strip();
                if ("ALL".equalsIgnoreCase(value) || value.isBlank()) return ALL_METRICS;

                List<String> metrics = new ArrayList<>();
                for (String m : value.split(",")) {
                    String trimmed = m.strip().toLowerCase();
                    if (ALL_METRICS.contains(trimmed)) {
                        metrics.add(trimmed);
                    }
                }
                return metrics.isEmpty() ? ALL_METRICS : metrics;
            }
        }
        return ALL_METRICS;
    }

    private String formatAttemptHistory(List<AttemptRecord> history) {
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (AttemptRecord r : history) {
            sb.append(String.format("- 第 %d 次: 参数=%s → %s\n", r.attempt, r.params, r.result));
        }
        return sb.toString();
    }

    private record QueryParams(int minutesAgo, List<String> metrics, String serviceName) {
        @Override
        public String toString() {
            return String.format("minutes=%d, metrics=%s, service=%s",
                    minutesAgo, metrics, serviceName != null ? serviceName : "ALL");
        }
    }

    private record AttemptRecord(int attempt, String params, String result) {}
}
