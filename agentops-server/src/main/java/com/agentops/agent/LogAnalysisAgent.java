package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import com.agentops.statemachine.ReflectionEngine;
import com.agentops.statemachine.ReflectionEngine.ReflectionResult;
import com.agentops.tool.LogQueryTool;
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
 * LogAnalysis Agent — 日志分析专家
 *
 * 核心能力:
 * 1. 提取高频错误消息（按出现频次降序）
 * 2. 识别时间段内的错误峰值（按分钟聚合的错误分布）
 * 3. 关联 trace_id 追踪完整调用链
 *
 * 执行流程 (Act-Observe-Reflect):
 * ┌─────────┐     ┌──────────────┐     ┌───────────┐     ┌───────────┐
 * │ THOUGHT │────▶│     ACT      │────▶│  OBSERVE  │────▶│  REFLECT  │
 * │ 理解任务 │     │ 多步骤日志查询 │     │ 聚合分析结果│     │ 判断是否补查│
 * └─────────┘     └──────────────┘     └───────────┘     └─────┬─────┘
 *                       ▲                                       │
 *                       │          补充查询（调整参数）             │
 *                       └───────────────────────────────────────┘
 *
 * 与 Text2SqlAgent 的区别:
 * - Text2SqlAgent 让 LLM 生成任意 SQL → 安全检查 → 执行
 * - LogAnalysisAgent 使用 LogQueryTool 的预定义查询（更可控），LLM 负责分析结果
 */
@Service
public class LogAnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(LogAnalysisAgent.class);

    private static final String AGENT_NAME = "LOG_ANALYSIS";
    private static final int MAX_ATTEMPTS = 3;

    /**
     * 日志分析 Prompt — 让 LLM 解读查询结果，提取关键发现
     */
    private static final String ANALYSIS_PROMPT_TEMPLATE = """
            你是一个微服务日志分析专家。请根据以下查询结果，分析错误模式并给出诊断结论。

            ## 用户问题
            %s

            ## 分析任务
            %s

            ## 查询结果

            ### 高频错误 TOP 列表
            %s

            ### 错误时间分布（按分钟聚合）
            %s

            ### 错误详情（含堆栈摘要）
            %s

            ## 请输出以下分析（Markdown 格式）:
            1. **错误模式**: 概述主要错误类型和集中爆发的服务
            2. **峰值时段**: 错误数量最多的时间段，是否有突发性变化
            3. **关联分析**: 不同错误之间是否存在因果关系（如连接池耗尽→超时→服务不可用）
            4. **可疑 trace_id**: 如果结果中有 trace_id，列出最值得追查的 1-3 个
            5. **结论**: 一句话概括日志中暴露的核心问题
            """;

    /**
     * 任务解析 Prompt — 从自然语言任务中提取查询参数
     */
    private static final String TASK_PARSE_PROMPT = """
            从以下任务描述中提取查询参数。严格按格式输出，每行一个参数。

            任务: %s
            用户问题: %s

            请输出（如果无法确定则使用默认值）:
            MINUTES=<查询最近多少分钟的数据，默认30>
            SERVICE=<服务名过滤，如果未指定则输出 ALL>
            ERROR_LEVEL=<错误级别过滤 ERROR/WARN/FATAL，如果未指定则输出 ALL>
            """;

    private final ChatLanguageModel workerModel;
    private final LogQueryTool logQueryTool;
    private final ReflectionEngine reflectionEngine;
    private final TraceStore traceStore;

    public LogAnalysisAgent(@Qualifier("workerModel") ChatLanguageModel workerModel,
                             LogQueryTool logQueryTool,
                             ReflectionEngine reflectionEngine,
                             TraceStore traceStore) {
        this.workerModel = workerModel;
        this.logQueryTool = logQueryTool;
        this.reflectionEngine = reflectionEngine;
        this.traceStore = traceStore;
    }

    /**
     * 执行日志分析任务（Act-Observe-Reflect 循环）
     *
     * @param task          子任务描述（如"分析 order-service 最近 30 分钟的错误日志"）
     * @param sessionId     诊断会话 ID
     * @param originalQuery 用户原始问题
     * @return Agent 执行结果
     */
    public AgentResult execute(String task, String sessionId, String originalQuery) {
        log.info("LogAnalysisAgent 开始执行 [session={}]: task={}", sessionId, task);

        // ======================== THOUGHT ========================
        String thought = String.format(
                "收到任务: %s\n用户问题: %s\n"
                + "计划: 1.解析查询参数 → 2.查询高频错误 → 3.查询错误时间分布 → 4.查询错误详情 → 5.LLM 综合分析",
                task, originalQuery);
        traceStore.recordThought(sessionId, AGENT_NAME, thought);

        List<AttemptRecord> attemptHistory = new ArrayList<>();

        // ======================== ACT-OBSERVE-REFLECT LOOP ========================
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            log.info("LogAnalysisAgent 第 {} 次尝试 [session={}]", attempt, sessionId);

            // ---------- ACT Step 1: 解析查询参数 ----------
            QueryParams params = parseTaskParams(task, originalQuery, attemptHistory, sessionId);
            traceStore.recordAction(sessionId, AGENT_NAME, "PARSE_TASK_PARAMS",
                    Map.of("task", task, "attempt", attempt),
                    params.toString(), true);

            // ---------- ACT Step 2: 执行多维查询 ----------

            // 2a. 高频错误 TOP
            SqlExecutionResult topErrors = logQueryTool.queryTopErrors(
                    params.minutesAgo, params.serviceName, 20);
            traceStore.recordAction(sessionId, AGENT_NAME, "QUERY_TOP_ERRORS",
                    Map.of("minutesAgo", params.minutesAgo,
                           "serviceName", params.serviceDisplay()),
                    topErrors.toFormattedText(), topErrors.isSuccess());

            // 2b. 错误时间分布
            SqlExecutionResult timeline = logQueryTool.queryErrorTimeline(
                    params.minutesAgo, params.serviceName);
            traceStore.recordAction(sessionId, AGENT_NAME, "QUERY_ERROR_TIMELINE",
                    Map.of("minutesAgo", params.minutesAgo),
                    timeline.toFormattedText(), timeline.isSuccess());

            // 2c. 错误详情（如果有指定服务名）
            SqlExecutionResult details;
            if (params.serviceName != null) {
                details = logQueryTool.queryErrorDetails(
                        params.serviceName, params.minutesAgo, params.errorLevel);
                traceStore.recordAction(sessionId, AGENT_NAME, "QUERY_ERROR_DETAILS",
                        Map.of("serviceName", params.serviceName,
                               "errorLevel", params.levelDisplay()),
                        details.toFormattedText(), details.isSuccess());
            } else {
                details = SqlExecutionResult.empty(0);
            }

            // ---------- OBSERVE: 检查查询结果 ----------

            // 检查是否有执行错误
            String queryError = checkQueryErrors(topErrors, timeline, details);
            if (queryError != null) {
                String observation = String.format(
                        "查询执行出错（第 %d 次）: %s", attempt, queryError);
                traceStore.recordObservation(sessionId, AGENT_NAME, observation);
                attemptHistory.add(new AttemptRecord(attempt, params.toString(), "查询出错: " + queryError));

                if (attempt < MAX_ATTEMPTS) {
                    ReflectionResult reflection = reflectionEngine.reflect(
                            task, originalQuery, "LogQueryTool 预定义查询",
                            queryError, formatAttemptHistory(attemptHistory));
                    traceStore.recordReflection(sessionId, AGENT_NAME, reflection.toPromptText());
                    if (!reflection.shouldRetry()) {
                        return AgentResult.failure(AGENT_NAME, "日志查询失败: " + queryError);
                    }
                }
                continue;
            }

            // 所有查询都为空
            if (topErrors.isEmpty() && timeline.isEmpty()) {
                String observation = String.format(
                        "所有日志查询结果为空（第 %d 次），参数: %s", attempt, params);
                traceStore.recordObservation(sessionId, AGENT_NAME, observation);
                attemptHistory.add(new AttemptRecord(attempt, params.toString(), "结果全部为空"));

                if (attempt < MAX_ATTEMPTS) {
                    ReflectionResult reflection = reflectionEngine.reflect(
                            task, originalQuery, "查询参数: " + params,
                            "所有日志查询结果为空，可能时间范围不对或服务名不匹配",
                            formatAttemptHistory(attemptHistory));
                    traceStore.recordReflection(sessionId, AGENT_NAME, reflection.toPromptText());
                    if (!reflection.shouldRetry()) {
                        return AgentResult.success(AGENT_NAME,
                                "查询时间段内无错误日志记录",
                                "未发现错误日志，该时间段内服务运行正常或日志未被收集");
                    }
                }
                continue;
            }

            // ---------- ACT Step 3: LLM 综合分析 ----------
            String analysisResult = analyzeResults(task, originalQuery,
                    topErrors, timeline, details, sessionId);
            traceStore.recordAction(sessionId, AGENT_NAME, "LLM_ANALYSIS",
                    Map.of("attempt", attempt),
                    analysisResult, true);

            // ---------- OBSERVE: 分析完成 ----------
            int totalErrors = topErrors.getRowCount() + timeline.getRowCount();
            String successObs = String.format(
                    "日志分析完成（第 %d 次）: 高频错误 %d 类，时间线 %d 个时段",
                    attempt, topErrors.getRowCount(), timeline.getRowCount());
            traceStore.recordObservation(sessionId, AGENT_NAME, successObs);
            traceStore.recordDecision(sessionId, AGENT_NAME,
                    "日志分析完成，已识别错误模式和峰值时段");

            log.info("LogAnalysisAgent 执行成功 [session={}]: 第 {} 次尝试", sessionId, attempt);

            // 构建数据摘要
            StringBuilder dataSummary = new StringBuilder();
            dataSummary.append("## 高频错误 TOP\n").append(topErrors.toFormattedText()).append("\n\n");
            dataSummary.append("## 错误时间分布\n").append(timeline.toFormattedText()).append("\n\n");
            if (!details.isEmpty()) {
                dataSummary.append("## 错误详情\n").append(details.toFormattedText()).append("\n\n");
            }
            dataSummary.append("## AI 分析结论\n").append(analysisResult);

            return AgentResult.success(AGENT_NAME, dataSummary.toString(),
                    String.format("分析了最近 %d 分钟的日志，发现 %d 类高频错误",
                            params.minutesAgo, topErrors.getRowCount()));
        }

        // ======================== 所有重试用尽 ========================
        log.warn("LogAnalysisAgent 所有重试用尽 [session={}]", sessionId);
        traceStore.recordDecision(sessionId, AGENT_NAME,
                "已尝试 " + MAX_ATTEMPTS + " 次，均未获得有效结果");

        AttemptRecord lastAttempt = attemptHistory.get(attemptHistory.size() - 1);
        return AgentResult.failure(AGENT_NAME,
                String.format("经过 %d 次尝试均未成功。最后一次: %s", MAX_ATTEMPTS, lastAttempt.result));
    }

    /**
     * 解析任务参数 — 调用 LLM 从自然语言中提取查询条件
     *
     * 如果 LLM 调用失败，使用保守的默认值（30 分钟，不过滤服务和级别）
     */
    private QueryParams parseTaskParams(String task, String originalQuery,
                                         List<AttemptRecord> history, String sessionId) {
        // 有历史尝试时，根据反思调整参数
        if (!history.isEmpty()) {
            AttemptRecord last = history.get(history.size() - 1);
            // 如果上次结果为空，扩大时间范围
            if (last.result.contains("为空")) {
                QueryParams expanded = parseFromLlm(task, originalQuery, sessionId);
                return new QueryParams(
                        Math.min(expanded.minutesAgo * 2, 1440), // 扩大一倍，最多24小时
                        null, // 去掉服务名过滤
                        null  // 去掉级别过滤
                );
            }
        }

        return parseFromLlm(task, originalQuery, sessionId);
    }

    /**
     * 调用 LLM 解析参数
     */
    private QueryParams parseFromLlm(String task, String originalQuery, String sessionId) {
        try {
            String prompt = String.format(TASK_PARSE_PROMPT, task, originalQuery);
            String response = workerModel.generate(prompt);

            int minutes = extractIntParam(response, "MINUTES", 30);
            String service = extractStringParam(response, "SERVICE");
            String level = extractStringParam(response, "ERROR_LEVEL");

            return new QueryParams(minutes, service, level);
        } catch (Exception e) {
            log.warn("LLM 解析任务参数失败 [session={}]: {}，使用默认值", sessionId, e.getMessage());
            return new QueryParams(30, null, null);
        }
    }

    /**
     * 调用 LLM 分析查询结果
     */
    private String analyzeResults(String task, String originalQuery,
                                   SqlExecutionResult topErrors,
                                   SqlExecutionResult timeline,
                                   SqlExecutionResult details,
                                   String sessionId) {
        String prompt = String.format(ANALYSIS_PROMPT_TEMPLATE,
                originalQuery, task,
                topErrors.toFormattedText(),
                timeline.toFormattedText(),
                details.isEmpty() ? "未查询错误详情" : details.toFormattedText());

        try {
            return workerModel.generate(prompt);
        } catch (Exception e) {
            log.error("LLM 分析调用失败 [session={}]: {}", sessionId, e.getMessage());
            return "LLM 分析失败: " + e.getMessage() + "\n请人工查看上方原始查询数据。";
        }
    }

    /**
     * 检查多个查询是否有执行错误
     */
    private String checkQueryErrors(SqlExecutionResult... results) {
        for (SqlExecutionResult r : results) {
            if (r.hasError()) return r.getError();
        }
        return null;
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

    private String formatAttemptHistory(List<AttemptRecord> history) {
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (AttemptRecord r : history) {
            sb.append(String.format("- 第 %d 次: 参数=%s → %s\n", r.attempt, r.params, r.result));
        }
        return sb.toString();
    }

    /**
     * 查询参数
     */
    private record QueryParams(int minutesAgo, String serviceName, String errorLevel) {
        String serviceDisplay() { return serviceName != null ? serviceName : "ALL"; }
        String levelDisplay() { return errorLevel != null ? errorLevel : "ALL"; }

        @Override
        public String toString() {
            return String.format("minutes=%d, service=%s, level=%s",
                    minutesAgo, serviceDisplay(), levelDisplay());
        }
    }

    private record AttemptRecord(int attempt, String params, String result) {}
}
