package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import com.agentops.agent.model.DiagnosisResult;
import com.agentops.agent.model.DiagnosisResult.DiagnosisReport;
import com.agentops.agent.model.TaskPlan;
import com.agentops.agent.model.TaskPlan.TaskItem;
import com.agentops.domain.entity.DiagnosisSession;
import com.agentops.repository.SessionRepository;
import com.agentops.trace.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouterAgentTest {

    @Mock private ChatLanguageModel routerModel;
    @Mock private Text2SqlAgent text2SqlAgent;
    @Mock private LogAnalysisAgent logAnalysisAgent;
    @Mock private MetricQueryAgent metricQueryAgent;
    @Mock private ReportAgent reportAgent;
    @Mock private TraceStore traceStore;
    @Mock private SessionRepository sessionRepo;

    private RouterAgent routerAgent;

    private static final String SESSION_ID = "test-session-001";
    private static final String USER_QUERY = "最近10分钟数据库响应变慢，帮我排查";

    /** LLM 正常返回的 JSON */
    private static final String LLM_NORMAL_RESPONSE = """
            {
              "intent": "DB_SLOW_QUERY",
              "confidence": 0.92,
              "tasks": [
                {"agent": "TEXT2SQL", "task": "查询最近10分钟的慢SQL记录", "priority": 1},
                {"agent": "METRIC_QUERY", "task": "查询数据库QPS和延迟趋势", "priority": 1},
                {"agent": "LOG_ANALYSIS", "task": "查看order-service是否有报错", "priority": 2}
              ],
              "reasoning": "用户描述了数据库响应变慢，需要先查慢SQL和指标，再看日志"
            }
            """;

    @BeforeEach
    void setUp() {
        routerAgent = new RouterAgent(
                routerModel,
                text2SqlAgent,
                logAnalysisAgent,
                metricQueryAgent,
                reportAgent,
                traceStore,
                sessionRepo,
                new ObjectMapper(),
                Executors.newFixedThreadPool(4));

        // 默认 mock: sessionRepo.save 返回传入对象
        when(sessionRepo.save(any(DiagnosisSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // =====================================================================
    // JSON 解析
    // =====================================================================

    @Nested
    @DisplayName("TaskPlan JSON 解析")
    class TaskPlanParsing {

        @Test
        @DisplayName("解析标准 JSON")
        void parseStandardJson() {
            TaskPlan plan = routerAgent.parseTaskPlan(LLM_NORMAL_RESPONSE);

            assertEquals("DB_SLOW_QUERY", plan.getIntent());
            assertEquals(0.92, plan.getConfidence(), 0.01);
            assertEquals(3, plan.getTasks().size());
            assertEquals("TEXT2SQL", plan.getTasks().get(0).getAgent());
            assertEquals(1, plan.getTasks().get(0).getPriority());
            assertEquals(2, plan.getTasks().get(2).getPriority());
            assertNotNull(plan.getReasoning());
        }

        @Test
        @DisplayName("解析 markdown code block 包裹的 JSON")
        void parseMarkdownWrapped() {
            String wrapped = "根据分析，任务计划如下：\n```json\n" + LLM_NORMAL_RESPONSE + "\n```\n";
            TaskPlan plan = routerAgent.parseTaskPlan(wrapped);

            assertEquals("DB_SLOW_QUERY", plan.getIntent());
            assertEquals(3, plan.getTasks().size());
        }

        @Test
        @DisplayName("解析前后有多余文本的 JSON")
        void parseJsonWithSurroundingText() {
            String withText = "好的，这是我的分析：\n" + LLM_NORMAL_RESPONSE + "\n以上是计划。";
            TaskPlan plan = routerAgent.parseTaskPlan(withText);

            assertEquals("DB_SLOW_QUERY", plan.getIntent());
        }

        @Test
        @DisplayName("解析失败时降级为全量排查计划")
        void fallbackOnInvalidJson() {
            TaskPlan plan = routerAgent.parseTaskPlan("这不是一个有效的JSON响应");

            assertEquals("GENERAL", plan.getIntent());
            assertEquals(0.5, plan.getConfidence(), 0.01);
            assertEquals(3, plan.getTasks().size());
            // 降级计划应包含所有三个 Agent
            List<String> agents = plan.getTasks().stream()
                    .map(TaskItem::getAgent).toList();
            assertTrue(agents.contains("TEXT2SQL"));
            assertTrue(agents.contains("LOG_ANALYSIS"));
            assertTrue(agents.contains("METRIC_QUERY"));
        }

        @Test
        @DisplayName("解析空响应时降级")
        void fallbackOnEmpty() {
            TaskPlan plan = routerAgent.parseTaskPlan("");
            assertEquals("GENERAL", plan.getIntent());
        }
    }

    // =====================================================================
    // 任务分发
    // =====================================================================

    @Nested
    @DisplayName("Agent 任务分发")
    class AgentDispatching {

        @Test
        @DisplayName("TEXT2SQL 分发到 Text2SqlAgent")
        void dispatchToText2Sql() {
            TaskItem task = TaskItem.builder()
                    .agent("TEXT2SQL").task("查慢SQL").priority(1).build();
            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "data", "explain"));

            AgentResult result = routerAgent.dispatchToAgent(task, SESSION_ID, USER_QUERY);

            assertTrue(result.isSuccess());
            verify(text2SqlAgent).execute("查慢SQL", SESSION_ID, USER_QUERY);
        }

        @Test
        @DisplayName("LOG_ANALYSIS 分发到 LogAnalysisAgent")
        void dispatchToLogAnalysis() {
            TaskItem task = TaskItem.builder()
                    .agent("LOG_ANALYSIS").task("查错误日志").priority(1).build();
            when(logAnalysisAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("LOG_ANALYSIS", "data", "explain"));

            AgentResult result = routerAgent.dispatchToAgent(task, SESSION_ID, USER_QUERY);

            assertTrue(result.isSuccess());
            verify(logAnalysisAgent).execute("查错误日志", SESSION_ID, USER_QUERY);
        }

        @Test
        @DisplayName("METRIC_QUERY 分发到 MetricQueryAgent")
        void dispatchToMetricQuery() {
            TaskItem task = TaskItem.builder()
                    .agent("METRIC_QUERY").task("查CPU指标").priority(1).build();
            when(metricQueryAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("METRIC_QUERY", "data", "explain"));

            AgentResult result = routerAgent.dispatchToAgent(task, SESSION_ID, USER_QUERY);

            assertTrue(result.isSuccess());
            verify(metricQueryAgent).execute("查CPU指标", SESSION_ID, USER_QUERY);
        }

        @Test
        @DisplayName("未知 Agent 类型返回失败")
        void dispatchUnknownAgent() {
            TaskItem task = TaskItem.builder()
                    .agent("UNKNOWN_AGENT").task("test").priority(1).build();

            AgentResult result = routerAgent.dispatchToAgent(task, SESSION_ID, USER_QUERY);

            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("未知"));
        }

        @Test
        @DisplayName("Agent 抛异常时返回失败（不传播异常）")
        void dispatchHandlesException() {
            TaskItem task = TaskItem.builder()
                    .agent("TEXT2SQL").task("查慢SQL").priority(1).build();
            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("LLM API 故障"));

            AgentResult result = routerAgent.dispatchToAgent(task, SESSION_ID, USER_QUERY);

            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("LLM API 故障"));
        }

        @Test
        @DisplayName("分发时记录 Trace ACTION")
        void dispatchRecordsTrace() {
            TaskItem task = TaskItem.builder()
                    .agent("TEXT2SQL").task("查慢SQL").priority(1).build();
            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "data", "explain"));

            routerAgent.dispatchToAgent(task, SESSION_ID, USER_QUERY);

            verify(traceStore).recordAction(eq(SESSION_ID), eq("ROUTER"),
                    eq("dispatch_agent"), anyMap(), any(), eq(true));
            verify(traceStore).recordObservation(eq(SESSION_ID), eq("ROUTER"),
                    contains("TEXT2SQL"));
        }
    }

    // =====================================================================
    // 优先级并行执行
    // =====================================================================

    @Nested
    @DisplayName("按 priority 分组执行")
    class PriorityExecution {

        @Test
        @DisplayName("相同 priority 的任务并行执行")
        void samePriorityParallel() {
            TaskPlan plan = TaskPlan.builder()
                    .intent("DB_SLOW_QUERY").confidence(0.9)
                    .tasks(List.of(
                            TaskItem.builder().agent("TEXT2SQL")
                                    .task("查慢SQL").priority(1).build(),
                            TaskItem.builder().agent("METRIC_QUERY")
                                    .task("查指标").priority(1).build()
                    )).build();

            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "slow queries", "explain1"));
            when(metricQueryAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("METRIC_QUERY", "metrics", "explain2"));

            Map<String, AgentResult> results = routerAgent.executeTasksByPriority(
                    plan, SESSION_ID, USER_QUERY);

            assertEquals(2, results.size());
            assertTrue(results.get("TEXT2SQL").isSuccess());
            assertTrue(results.get("METRIC_QUERY").isSuccess());
        }

        @Test
        @DisplayName("不同 priority 按顺序执行")
        void differentPrioritySequential() {
            TaskPlan plan = TaskPlan.builder()
                    .intent("DB_SLOW_QUERY").confidence(0.9)
                    .tasks(List.of(
                            TaskItem.builder().agent("TEXT2SQL")
                                    .task("查慢SQL").priority(1).build(),
                            TaskItem.builder().agent("LOG_ANALYSIS")
                                    .task("查日志").priority(2).build()
                    )).build();

            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "data", "explain"));
            when(logAnalysisAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("LOG_ANALYSIS", "logs", "explain"));

            Map<String, AgentResult> results = routerAgent.executeTasksByPriority(
                    plan, SESSION_ID, USER_QUERY);

            assertEquals(2, results.size());
            // 验证 priority=1 的 TEXT2SQL 在结果中排在前面（LinkedHashMap 保序）
            List<String> order = List.copyOf(results.keySet());
            assertEquals("TEXT2SQL", order.get(0));
            assertEquals("LOG_ANALYSIS", order.get(1));
        }

        @Test
        @DisplayName("单个 Agent 失败不影响其他 Agent")
        void singleFailureDoesNotBlockOthers() {
            TaskPlan plan = TaskPlan.builder()
                    .intent("DB_SLOW_QUERY").confidence(0.9)
                    .tasks(List.of(
                            TaskItem.builder().agent("TEXT2SQL")
                                    .task("查慢SQL").priority(1).build(),
                            TaskItem.builder().agent("METRIC_QUERY")
                                    .task("查指标").priority(1).build()
                    )).build();

            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("SQL Agent 崩溃"));
            when(metricQueryAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("METRIC_QUERY", "metrics", "explain"));

            Map<String, AgentResult> results = routerAgent.executeTasksByPriority(
                    plan, SESSION_ID, USER_QUERY);

            assertEquals(2, results.size());
            assertFalse(results.get("TEXT2SQL").isSuccess());
            assertTrue(results.get("METRIC_QUERY").isSuccess());
        }

        @Test
        @DisplayName("单任务 priority 直接执行不用线程池")
        void singleTaskDirectExecution() {
            TaskPlan plan = TaskPlan.builder()
                    .intent("GENERAL").confidence(0.5)
                    .tasks(List.of(
                            TaskItem.builder().agent("TEXT2SQL")
                                    .task("查慢SQL").priority(1).build()
                    )).build();

            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "data", "explain"));

            Map<String, AgentResult> results = routerAgent.executeTasksByPriority(
                    plan, SESSION_ID, USER_QUERY);

            assertEquals(1, results.size());
            assertTrue(results.get("TEXT2SQL").isSuccess());
        }
    }

    // =====================================================================
    // 完整诊断流程
    // =====================================================================

    @Nested
    @DisplayName("完整诊断流程 (diagnose)")
    class FullDiagnosisFlow {

        @Test
        @DisplayName("正常完整流程: 意图识别 → 派发 → 报告")
        void fullSuccessFlow() {
            // Mock LLM 意图识别
            when(routerModel.generate(anyString())).thenReturn(LLM_NORMAL_RESPONSE);

            // Mock Worker Agents
            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "慢SQL数据", "发现3条慢查询"));
            when(metricQueryAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("METRIC_QUERY", "指标数据", "CPU飙高"));
            when(logAnalysisAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("LOG_ANALYSIS", "日志数据", "order-service超时"));

            // Mock ReportAgent
            when(reportAgent.generateReport(anyString(), any(), anyMap(), anyString()))
                    .thenReturn(new DiagnosisReport(
                            "order_detail 表缺索引导致全表扫描",
                            "ALTER TABLE order_detail ADD INDEX idx_order_id (order_id)",
                            "# 诊断报告\n## 根因...",
                            0.88f));

            // Mock traceStore.getFullTrace
            when(traceStore.getFullTrace(anyString())).thenReturn(List.of());

            // 执行
            DiagnosisResult result = routerAgent.diagnose(USER_QUERY, SESSION_ID);

            // 验证结果
            assertNotNull(result);
            assertNotNull(result.getReport());
            assertEquals("order_detail 表缺索引导致全表扫描",
                    result.getReport().getRootCause());
            assertEquals(0.88f, result.getReport().getConfidence(), 0.01);

            // 验证会话状态
            DiagnosisSession session = result.getSession();
            assertEquals("COMPLETED", session.getStatus());
            assertEquals("DB_SLOW_QUERY", session.getIntentType());
            assertNotNull(session.getCompletedAt());

            // 验证各组件都被调用
            verify(routerModel).generate(anyString());
            verify(text2SqlAgent).execute(anyString(), eq(SESSION_ID), eq(USER_QUERY));
            verify(metricQueryAgent).execute(anyString(), eq(SESSION_ID), eq(USER_QUERY));
            verify(logAnalysisAgent).execute(anyString(), eq(SESSION_ID), eq(USER_QUERY));
            verify(reportAgent).generateReport(eq(USER_QUERY), any(), anyMap(), eq(SESSION_ID));
        }

        @Test
        @DisplayName("LLM 意图识别失败时使用降级计划继续执行")
        void fallbackWhenLlmFails() {
            // LLM 返回无效内容
            when(routerModel.generate(anyString())).thenReturn("我不理解你的问题");

            // Worker Agents 正常
            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "data", "explain"));
            when(logAnalysisAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("LOG_ANALYSIS", "data", "explain"));
            when(metricQueryAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("METRIC_QUERY", "data", "explain"));
            when(reportAgent.generateReport(anyString(), any(), anyMap(), anyString()))
                    .thenReturn(new DiagnosisReport("未知", "需要更多信息", "# 报告", 0.3f));
            when(traceStore.getFullTrace(anyString())).thenReturn(List.of());

            DiagnosisResult result = routerAgent.diagnose(USER_QUERY, SESSION_ID);

            assertNotNull(result);
            assertEquals("COMPLETED", result.getSession().getStatus());
            // 使用降级计划，意图为 GENERAL
            assertEquals("GENERAL", result.getSession().getIntentType());

            // 降级计划应触发所有3个 Agent
            verify(text2SqlAgent).execute(anyString(), eq(SESSION_ID), eq(USER_QUERY));
            verify(logAnalysisAgent).execute(anyString(), eq(SESSION_ID), eq(USER_QUERY));
            verify(metricQueryAgent).execute(anyString(), eq(SESSION_ID), eq(USER_QUERY));
        }

        @Test
        @DisplayName("全部 Worker 失败，仍生成报告并标记 COMPLETED")
        void allAgentsFailStillCompletes() {
            when(routerModel.generate(anyString())).thenReturn(LLM_NORMAL_RESPONSE);

            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.failure("TEXT2SQL", "连接超时"));
            when(metricQueryAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.failure("METRIC_QUERY", "Prometheus 不可用"));
            when(logAnalysisAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.failure("LOG_ANALYSIS", "ES 集群故障"));
            when(reportAgent.generateReport(anyString(), any(), anyMap(), anyString()))
                    .thenReturn(new DiagnosisReport("无法确定", "数据不足", "# 报告", 0.1f));
            when(traceStore.getFullTrace(anyString())).thenReturn(List.of());

            DiagnosisResult result = routerAgent.diagnose(USER_QUERY, SESSION_ID);

            assertEquals("COMPLETED", result.getSession().getStatus());
            assertNotNull(result.getReport());
        }

        @Test
        @DisplayName("ReportAgent 异常时会话标记 FAILED")
        void reportAgentExceptionMarksFailed() {
            when(routerModel.generate(anyString())).thenReturn(LLM_NORMAL_RESPONSE);

            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "data", "explain"));
            when(metricQueryAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("METRIC_QUERY", "data", "explain"));
            when(logAnalysisAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("LOG_ANALYSIS", "data", "explain"));
            when(reportAgent.generateReport(anyString(), any(), anyMap(), anyString()))
                    .thenThrow(new RuntimeException("LLM API 限流"));
            when(traceStore.getFullTrace(anyString())).thenReturn(List.of());

            DiagnosisResult result = routerAgent.diagnose(USER_QUERY, SESSION_ID);

            assertEquals("FAILED", result.getSession().getStatus());
            assertNull(result.getReport());
        }
    }

    // =====================================================================
    // Trace 记录验证
    // =====================================================================

    @Nested
    @DisplayName("全链路追踪记录")
    class TraceRecording {

        @Test
        @DisplayName("完整流程记录所有关键 Trace")
        void recordsAllTraceSteps() {
            when(routerModel.generate(anyString())).thenReturn(LLM_NORMAL_RESPONSE);
            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "data", "explain"));
            when(metricQueryAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("METRIC_QUERY", "data", "explain"));
            when(logAnalysisAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("LOG_ANALYSIS", "data", "explain"));
            when(reportAgent.generateReport(anyString(), any(), anyMap(), anyString()))
                    .thenReturn(new DiagnosisReport("根因", "建议", "# 报告", 0.9f));
            when(traceStore.getFullTrace(anyString())).thenReturn(List.of());

            routerAgent.diagnose(USER_QUERY, SESSION_ID);

            // 验证关键 Trace 类型都被记录
            // THOUGHT: 至少3次（开始分析、开始派发、触发报告）
            verify(traceStore, atLeast(3))
                    .recordThought(eq(SESSION_ID), eq("ROUTER"), anyString());

            // DECISION: 至少2次（意图识别结果、诊断完成）
            verify(traceStore, atLeast(2))
                    .recordDecision(eq(SESSION_ID), eq("ROUTER"), anyString());

            // ACTION: 意图分类 + 3个 Agent 派发 = 至少4次
            verify(traceStore, atLeast(4))
                    .recordAction(eq(SESSION_ID), eq("ROUTER"),
                            anyString(), anyMap(), any(), anyBoolean());

            // OBSERVATION: Worker 完成汇总 + 每个 Agent 返回 = 至少4次
            verify(traceStore, atLeast(4))
                    .recordObservation(eq(SESSION_ID), eq("ROUTER"), anyString());
        }

        @Test
        @DisplayName("意图识别时记录 LLM 调用 Action")
        void recordsLlmCallAction() {
            when(routerModel.generate(anyString())).thenReturn(LLM_NORMAL_RESPONSE);

            routerAgent.identifyIntentAndPlan(USER_QUERY, SESSION_ID);

            ArgumentCaptor<Map<String, Object>> inputCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(traceStore).recordAction(eq(SESSION_ID), eq("ROUTER"),
                    eq("llm_intent_classify"), inputCaptor.capture(),
                    eq(LLM_NORMAL_RESPONSE.strip()), eq(true));

            Map<String, Object> input = inputCaptor.getValue();
            assertEquals(USER_QUERY, input.get("query"));
            assertEquals("gpt-4o", input.get("model"));
        }
    }

    // =====================================================================
    // 会话状态管理
    // =====================================================================

    @Nested
    @DisplayName("会话状态流转")
    class SessionStateManagement {

        @Test
        @DisplayName("会话状态流转: CREATED → ROUTING → EXECUTING → COMPLETED")
        void sessionStateTransitions() {
            when(routerModel.generate(anyString())).thenReturn(LLM_NORMAL_RESPONSE);
            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "data", "explain"));
            when(metricQueryAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("METRIC_QUERY", "data", "explain"));
            when(logAnalysisAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("LOG_ANALYSIS", "data", "explain"));
            when(reportAgent.generateReport(anyString(), any(), anyMap(), anyString()))
                    .thenReturn(new DiagnosisReport("根因", "建议", "报告", 0.9f));
            when(traceStore.getFullTrace(anyString())).thenReturn(List.of());

            routerAgent.diagnose(USER_QUERY, SESSION_ID);

            // 验证 save 被多次调用，捕获所有 status 值
            ArgumentCaptor<DiagnosisSession> captor =
                    ArgumentCaptor.forClass(DiagnosisSession.class);
            verify(sessionRepo, atLeast(4)).save(captor.capture());

            List<String> statuses = captor.getAllValues().stream()
                    .map(DiagnosisSession::getStatus).toList();

            assertTrue(statuses.contains("CREATED"));
            assertTrue(statuses.contains("ROUTING"));
            assertTrue(statuses.contains("EXECUTING"));
            assertTrue(statuses.contains("COMPLETED"));
        }

        @Test
        @DisplayName("title 超过 80 字符自动截断")
        void titleTruncation() {
            String longQuery = "这是一个非常非常非常非常非常长的问题描述，"
                    + "包含很多很多很多的细节信息，用来测试标题截断功能是否正常工作，"
                    + "如果不截断会导致数据库存储异常";

            when(routerModel.generate(anyString())).thenReturn(LLM_NORMAL_RESPONSE);
            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "data", "explain"));
            when(metricQueryAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("METRIC_QUERY", "data", "explain"));
            when(logAnalysisAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("LOG_ANALYSIS", "data", "explain"));
            when(reportAgent.generateReport(anyString(), any(), anyMap(), anyString()))
                    .thenReturn(new DiagnosisReport("根因", "建议", "报告", 0.9f));
            when(traceStore.getFullTrace(anyString())).thenReturn(List.of());

            routerAgent.diagnose(longQuery, SESSION_ID);

            ArgumentCaptor<DiagnosisSession> captor =
                    ArgumentCaptor.forClass(DiagnosisSession.class);
            verify(sessionRepo, atLeast(1)).save(captor.capture());

            DiagnosisSession first = captor.getAllValues().get(0);
            assertTrue(first.getTitle().length() <= 80);
            assertTrue(first.getTitle().endsWith("..."));
        }

        @Test
        @DisplayName("COMPLETED 时设置 totalLatencyMs")
        void completedSetsLatency() {
            when(routerModel.generate(anyString())).thenReturn(LLM_NORMAL_RESPONSE);
            when(text2SqlAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("TEXT2SQL", "data", "explain"));
            when(metricQueryAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("METRIC_QUERY", "data", "explain"));
            when(logAnalysisAgent.execute(anyString(), anyString(), anyString()))
                    .thenReturn(AgentResult.success("LOG_ANALYSIS", "data", "explain"));
            when(reportAgent.generateReport(anyString(), any(), anyMap(), anyString()))
                    .thenReturn(new DiagnosisReport("根因", "建议", "报告", 0.9f));
            when(traceStore.getFullTrace(anyString())).thenReturn(List.of());

            DiagnosisResult result = routerAgent.diagnose(USER_QUERY, SESSION_ID);

            assertNotNull(result.getSession().getTotalLatencyMs());
            assertTrue(result.getSession().getTotalLatencyMs() >= 0);
        }
    }
}
