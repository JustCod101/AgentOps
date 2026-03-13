package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import com.agentops.agent.model.DiagnosisResult;
import com.agentops.agent.model.DiagnosisResult.DiagnosisReport;
import com.agentops.agent.model.TaskPlan;
import com.agentops.agent.model.TaskPlan.TaskItem;
import com.agentops.domain.entity.DiagnosisSession;
import com.agentops.repository.SessionRepository;
import com.agentops.trace.TraceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Router Agent — 多智能体编排核心
 *
 * 职责:
 * 1. 意图识别: 调用 GPT-4o 判断问题类型（DB_SLOW_QUERY / SERVICE_ERROR / ...）
 * 2. 任务分解: 将复杂问题拆解为多个子任务，分配给专职 Worker Agent
 * 3. Agent 派发: 按 priority 分组，相同 priority 并行执行
 * 4. 结果汇总: 收集所有 AgentResult，触发 ReportAgent 生成报告
 * 5. 全链路追踪: 每一步 Thought/Decision 通过 TraceStore 持久化
 */
@Service
public class RouterAgent {

    private static final Logger log = LoggerFactory.getLogger(RouterAgent.class);

    private static final String AGENT_NAME = "ROUTER";
    private static final int AGENT_TIMEOUT_SECONDS = 120;

    private static final String ROUTER_SYSTEM_PROMPT = """
            你是一个 AIOps 排障路由器。你的职责是分析用户的运维问题，
            判断意图类型，并将任务分解为多个子任务。

            可用的 Worker Agent:
            1. TEXT2SQL - 查询数据库监控数据（慢SQL、连接数、锁等待等）
            2. LOG_ANALYSIS - 分析服务错误日志（异常堆栈、错误模式等）
            3. METRIC_QUERY - 查询系统指标（CPU、内存、QPS、延迟等）

            意图类型:
            - DB_SLOW_QUERY: 数据库查询变慢
            - DB_CONNECTION: 数据库连接问题
            - SERVICE_ERROR: 微服务报错
            - SERVICE_LATENCY: 服务延迟问题
            - MEMORY_LEAK: 内存泄漏
            - GENERAL: 通用排查

            请严格按照以下 JSON 格式返回任务计划（不要包含任何其他文本）:
            {
              "intent": "DB_SLOW_QUERY",
              "confidence": 0.9,
              "tasks": [
                {"agent": "TEXT2SQL", "task": "查询最近10分钟的慢SQL记录", "priority": 1},
                {"agent": "METRIC_QUERY", "task": "查询数据库QPS和延迟趋势", "priority": 1},
                {"agent": "LOG_ANALYSIS", "task": "查看相关服务是否有报错", "priority": 2}
              ],
              "reasoning": "用户描述了数据库响应变慢的问题，需要..."
            }

            规则:
            1. priority 相同的任务可以并行执行，不同 priority 按顺序执行
            2. priority 数值越小越优先
            3. 至少分配 2 个不同的 Agent 以交叉验证
            4. task 描述要具体，包含时间范围和查询目标
            """;

    private final ChatLanguageModel routerModel;
    private final Text2SqlAgent text2SqlAgent;
    private final LogAnalysisAgent logAnalysisAgent;
    private final MetricQueryAgent metricQueryAgent;
    private final ReportAgent reportAgent;
    private final TraceStore traceStore;
    private final SessionRepository sessionRepo;
    private final ObjectMapper objectMapper;
    private final Executor agentExecutor;

    public RouterAgent(
            @Qualifier("routerModel") ChatLanguageModel routerModel,
            Text2SqlAgent text2SqlAgent,
            LogAnalysisAgent logAnalysisAgent,
            MetricQueryAgent metricQueryAgent,
            ReportAgent reportAgent,
            TraceStore traceStore,
            SessionRepository sessionRepo,
            ObjectMapper objectMapper,
            @Qualifier("agentExecutor") Executor agentExecutor) {
        this.routerModel = routerModel;
        this.text2SqlAgent = text2SqlAgent;
        this.logAnalysisAgent = logAnalysisAgent;
        this.metricQueryAgent = metricQueryAgent;
        this.reportAgent = reportAgent;
        this.traceStore = traceStore;
        this.sessionRepo = sessionRepo;
        this.objectMapper = objectMapper;
        this.agentExecutor = agentExecutor;
    }

    /**
     * 发起诊断：意图识别 → 任务分发 → 结果汇总 → 报告生成
     *
     * @param userQuery 用户原始问题
     * @param sessionId 会话 ID（由 Controller 生成）
     * @return 完整诊断结果
     */
    public DiagnosisResult diagnose(String userQuery, String sessionId) {
        long startTime = System.nanoTime();

        // 创建会话
        DiagnosisSession session = createSession(sessionId, userQuery);

        try {
            // ===== Step 1: 意图识别 + 任务分解 =====
            traceStore.recordThought(sessionId, AGENT_NAME,
                    "收到用户问题，开始分析意图: " + userQuery);

            session.setStatus("ROUTING");
            sessionRepo.save(session);

            TaskPlan plan = identifyIntentAndPlan(userQuery, sessionId);

            traceStore.recordDecision(sessionId, AGENT_NAME,
                    "意图: " + plan.getIntent()
                            + " (置信度: " + plan.getConfidence() + ")"
                            + ", 分解为 " + plan.getTasks().size() + " 个子任务"
                            + ", 推理: " + plan.getReasoning());

            session.setIntentType(plan.getIntent());
            session.setAgentCount(plan.getTasks().size());
            session.setStatus("EXECUTING");
            sessionRepo.save(session);

            // ===== Step 2: 按 priority 分组并行执行 =====
            traceStore.recordThought(sessionId, AGENT_NAME,
                    "开始按优先级派发子任务到 Worker Agent");

            Map<String, AgentResult> agentResults = executeTasksByPriority(
                    plan, sessionId, userQuery);

            int successCount = (int) agentResults.values().stream()
                    .filter(AgentResult::isSuccess).count();
            traceStore.recordObservation(sessionId, AGENT_NAME,
                    "所有 Worker 完成: " + successCount + "/" + agentResults.size() + " 成功");

            // ===== Step 3: 报告生成 =====
            traceStore.recordThought(sessionId, AGENT_NAME,
                    "触发 ReportAgent 生成诊断报告");

            DiagnosisReport report = reportAgent.generateReport(
                    userQuery, plan, agentResults, sessionId);

            // ===== Step 4: 更新会话 =====
            long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
            session.setRootCause(report.getRootCause());
            session.setFixSuggestion(report.getFixSuggestion());
            session.setReportMarkdown(report.getMarkdown());
            session.setConfidence(report.getConfidence());
            session.setStatus("COMPLETED");
            session.setCompletedAt(Instant.now());
            session.setTotalLatencyMs(totalLatency);
            sessionRepo.save(session);

            traceStore.recordDecision(sessionId, AGENT_NAME,
                    "诊断完成, 置信度: " + report.getConfidence()
                            + ", 总耗时: " + totalLatency + "ms");

            return new DiagnosisResult(session, report, traceStore.getFullTrace(sessionId));

        } catch (Exception e) {
            long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
            log.error("诊断失败 [session={}]: {}", sessionId, e.getMessage(), e);

            traceStore.recordObservation(sessionId, AGENT_NAME,
                    "诊断异常终止: " + e.getMessage());

            session.setStatus("FAILED");
            session.setTotalLatencyMs(totalLatency);
            session.setCompletedAt(Instant.now());
            sessionRepo.save(session);

            return new DiagnosisResult(session, null, traceStore.getFullTrace(sessionId));
        }
    }

    /**
     * 调用 LLM 识别意图并生成任务计划
     */
    TaskPlan identifyIntentAndPlan(String userQuery, String sessionId) {
        String llmResponse = routerModel.generate(
                ROUTER_SYSTEM_PROMPT + "\n\n用户问题: " + userQuery);

        traceStore.recordAction(sessionId, AGENT_NAME, "llm_intent_classify",
                Map.of("query", userQuery, "model", "MiniMax-M2.5"),
                llmResponse, true);

        return parseTaskPlan(llmResponse);
    }

    /**
     * 按 priority 分组执行子任务：相同 priority 并行，不同 priority 顺序执行
     */
    Map<String, AgentResult> executeTasksByPriority(
            TaskPlan plan, String sessionId, String originalQuery) {

        Map<String, AgentResult> allResults = new LinkedHashMap<>();

        // 按 priority 升序分组
        Map<Integer, List<TaskItem>> grouped = plan.getTasks().stream()
                .collect(Collectors.groupingBy(
                        TaskItem::getPriority,
                        TreeMap::new,
                        Collectors.toList()));

        for (Map.Entry<Integer, List<TaskItem>> entry : grouped.entrySet()) {
            int priority = entry.getKey();
            List<TaskItem> parallelTasks = entry.getValue();

            traceStore.recordThought(sessionId, AGENT_NAME,
                    "执行 priority=" + priority + " 的 "
                            + parallelTasks.size() + " 个任务"
                            + (parallelTasks.size() > 1 ? "（并行）" : ""));

            if (parallelTasks.size() == 1) {
                // 单个任务直接执行，不需要线程池开销
                TaskItem task = parallelTasks.get(0);
                AgentResult result = dispatchToAgent(task, sessionId, originalQuery);
                allResults.put(task.getAgent(), result);
            } else {
                // 多个任务并行执行
                Map<String, AgentResult> parallelResults =
                        executeInParallel(parallelTasks, sessionId, originalQuery);
                allResults.putAll(parallelResults);
            }
        }

        return allResults;
    }

    /**
     * 并行执行同一 priority 的多个任务，单个 Agent 超时不影响其他
     */
    private Map<String, AgentResult> executeInParallel(
            List<TaskItem> tasks, String sessionId, String originalQuery) {

        Map<String, AgentResult> results = new LinkedHashMap<>();

        // 构建 CompletableFuture 列表
        Map<String, CompletableFuture<AgentResult>> futures = new LinkedHashMap<>();
        for (TaskItem task : tasks) {
            CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(
                    () -> dispatchToAgent(task, sessionId, originalQuery),
                    agentExecutor);
            futures.put(task.getAgent(), future);
        }

        // 逐个收集结果，单个超时不影响其他
        for (Map.Entry<String, CompletableFuture<AgentResult>> entry : futures.entrySet()) {
            String agentName = entry.getKey();
            CompletableFuture<AgentResult> future = entry.getValue();

            try {
                AgentResult result = future.get(AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                results.put(agentName, result);
            } catch (TimeoutException e) {
                log.warn("Agent {} 执行超时 [session={}]", agentName, sessionId);
                traceStore.recordObservation(sessionId, AGENT_NAME,
                        "Agent " + agentName + " 执行超时 (" + AGENT_TIMEOUT_SECONDS + "s)");
                future.cancel(true);
                results.put(agentName, AgentResult.failure(agentName,
                        "执行超时 (" + AGENT_TIMEOUT_SECONDS + "s)"));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("Agent {} 执行异常 [session={}]: {}",
                        agentName, sessionId, cause.getMessage());
                traceStore.recordObservation(sessionId, AGENT_NAME,
                        "Agent " + agentName + " 执行异常: " + cause.getMessage());
                results.put(agentName, AgentResult.failure(agentName, cause.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.put(agentName, AgentResult.failure(agentName, "执行被中断"));
            }
        }

        return results;
    }

    /**
     * 根据 agent 名称分发到对应 Worker Agent
     */
    AgentResult dispatchToAgent(TaskItem task, String sessionId, String originalQuery) {
        String agentName = task.getAgent();

        traceStore.recordAction(sessionId, AGENT_NAME, "dispatch_agent",
                Map.of("agent", agentName, "task", task.getTask()),
                null, true);

        try {
            AgentResult result = switch (agentName) {
                case "TEXT2SQL" -> text2SqlAgent.execute(
                        task.getTask(), sessionId, originalQuery);
                case "LOG_ANALYSIS" -> logAnalysisAgent.execute(
                        task.getTask(), sessionId, originalQuery);
                case "METRIC_QUERY" -> metricQueryAgent.execute(
                        task.getTask(), sessionId, originalQuery);
                default -> AgentResult.failure(agentName, "未知的 Agent 类型: " + agentName);
            };

            traceStore.recordObservation(sessionId, AGENT_NAME,
                    "Agent " + agentName + " 返回: "
                            + (result.isSuccess() ? "成功" : "失败 - " + result.getErrorMessage()));

            return result;

        } catch (Exception e) {
            log.error("Agent {} 分发异常 [session={}]: {}", agentName, sessionId, e.getMessage());
            traceStore.recordObservation(sessionId, AGENT_NAME,
                    "Agent " + agentName + " 分发异常: " + e.getMessage());
            return AgentResult.failure(agentName, "Agent 执行异常: " + e.getMessage());
        }
    }

    /**
     * 解析 LLM 返回的 JSON 为 TaskPlan
     */
    TaskPlan parseTaskPlan(String llmResponse) {
        try {
            // 提取 JSON 部分（LLM 可能返回 ```json ... ``` 包裹的内容）
            String json = extractJson(llmResponse);
            return objectMapper.readValue(json, TaskPlan.class);
        } catch (JsonProcessingException e) {
            log.warn("TaskPlan JSON 解析失败, 使用默认计划. LLM 返回: {}", llmResponse);
            return buildFallbackPlan();
        }
    }

    /**
     * 从 LLM 响应中提取 JSON（处理 markdown code block 包裹的情况）
     */
    private String extractJson(String response) {
        String trimmed = response.strip();

        // 尝试提取 ```json ... ``` 包裹的内容
        int jsonStart = trimmed.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = trimmed.indexOf('\n', jsonStart) + 1;
            int contentEnd = trimmed.indexOf("```", contentStart);
            if (contentEnd > contentStart) {
                return trimmed.substring(contentStart, contentEnd).strip();
            }
        }

        // 尝试提取 ``` ... ``` 包裹的内容
        if (trimmed.startsWith("```")) {
            int contentStart = trimmed.indexOf('\n') + 1;
            int contentEnd = trimmed.lastIndexOf("```");
            if (contentEnd > contentStart) {
                return trimmed.substring(contentStart, contentEnd).strip();
            }
        }

        // 尝试提取第一个 { ... 最后一个 }
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return trimmed;
    }

    /**
     * 当 LLM 返回格式异常时的降级计划：全量排查
     */
    private TaskPlan buildFallbackPlan() {
        return TaskPlan.builder()
                .intent("GENERAL")
                .confidence(0.5)
                .reasoning("LLM 返回格式异常，使用默认全量排查计划")
                .tasks(List.of(
                        TaskPlan.TaskItem.builder()
                                .agent("TEXT2SQL")
                                .task("查询最近30分钟的慢SQL记录和数据库连接状态")
                                .priority(1).build(),
                        TaskPlan.TaskItem.builder()
                                .agent("LOG_ANALYSIS")
                                .task("查询最近30分钟所有服务的ERROR级别日志")
                                .priority(1).build(),
                        TaskPlan.TaskItem.builder()
                                .agent("METRIC_QUERY")
                                .task("查询最近30分钟的CPU、内存、QPS和P99延迟指标")
                                .priority(1).build()
                ))
                .build();
    }

    private DiagnosisSession createSession(String sessionId, String userQuery) {
        DiagnosisSession session = DiagnosisSession.builder()
                .sessionId(sessionId)
                .initialQuery(userQuery)
                .title(truncateTitle(userQuery))
                .status("CREATED")
                .build();
        return sessionRepo.save(session);
    }

    private String truncateTitle(String query) {
        return query.length() <= 80 ? query : query.substring(0, 77) + "...";
    }
}
