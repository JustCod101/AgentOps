package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import com.agentops.statemachine.ReflectionEngine;
import com.agentops.statemachine.ReflectionEngine.ReflectionResult;
import com.agentops.tool.LogQueryTool;
import com.agentops.tool.SqlExecutionResult;
import com.agentops.trace.TraceStore;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LogAnalysisAgent 测试")
class LogAnalysisAgentTest {

    @Mock private ChatLanguageModel workerModel;
    @Mock private LogQueryTool logQueryTool;
    @Mock private ReflectionEngine reflectionEngine;
    @Mock private TraceStore traceStore;

    private LogAnalysisAgent agent;

    private static final String SESSION_ID = "log-test-001";
    private static final String QUERY = "order-service 频繁报错";
    private static final String TASK = "分析 order-service 最近 30 分钟的错误日志";

    @BeforeEach
    void setUp() {
        agent = new LogAnalysisAgent(workerModel, logQueryTool, reflectionEngine, traceStore);
    }

    // ================================================================
    // 1. 首次成功场景
    // ================================================================

    @Nested
    @DisplayName("首次成功场景")
    class FirstAttemptSuccess {

        @Test
        @DisplayName("查询到高频错误+时间分布 → LLM 分析 → 成功返回")
        void happyPath() {
            // LLM 解析任务参数
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=30\nSERVICE=order-service\nERROR_LEVEL=ALL");

            // 高频错误查询返回数据
            when(logQueryTool.queryTopErrors(eq(30), eq("order-service"), eq(20)))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("error_message", "HikariPool-1 - Connection is not available",
                                      "error_count", 45, "error_level", "ERROR",
                                      "service_name", "order-service"),
                            createRow("error_message", "SocketTimeoutException: Read timed out",
                                      "error_count", 23, "error_level", "ERROR",
                                      "service_name", "order-service")
                    ), 15));

            // 时间分布查询返回数据
            when(logQueryTool.queryErrorTimeline(eq(30), eq("order-service")))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "2024-01-01 10:00", "error_count", 5),
                            createRow("time_bucket", "2024-01-01 10:15", "error_count", 38)
                    ), 10));

            // 错误详情查询
            when(logQueryTool.queryErrorDetails(eq("order-service"), eq(30), isNull()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("error_message", "Connection pool exhausted",
                                      "trace_id", "abc-123")
                    ), 8));

            // LLM 分析结果
            when(workerModel.generate(contains("错误模式")))
                    .thenReturn("**错误模式**: 连接池耗尽导致级联故障\n**结论**: order-service 数据库连接池已满");

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAgentName()).isEqualTo("LOG_ANALYSIS");
            assertThat(result.getData()).contains("高频错误");
            assertThat(result.getData()).contains("AI 分析结论");
            verify(traceStore).recordThought(eq(SESSION_ID), eq("LOG_ANALYSIS"), anyString());
            verify(traceStore).recordDecision(eq(SESSION_ID), eq("LOG_ANALYSIS"), anyString());
            verifyNoInteractions(reflectionEngine);
        }

        @Test
        @DisplayName("未指定服务名 → 查询所有服务 → 成功")
        void noServiceFilter() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=60\nSERVICE=ALL\nERROR_LEVEL=ALL");

            when(logQueryTool.queryTopErrors(eq(60), isNull(), eq(20)))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("error_message", "Timeout", "error_count", 10,
                                      "service_name", "gateway")
                    ), 5));
            when(logQueryTool.queryErrorTimeline(eq(60), isNull()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "2024-01-01 10:00", "error_count", 10)
                    ), 3));

            when(workerModel.generate(contains("错误模式")))
                    .thenReturn("网关超时");

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            assertThat(result.isSuccess()).isTrue();
            // 无 service_name 时不调用 queryErrorDetails
            verify(logQueryTool, never()).queryErrorDetails(anyString(), anyInt(), anyString());
        }
    }

    // ================================================================
    // 2. 反思重试场景
    // ================================================================

    @Nested
    @DisplayName("反思重试场景")
    class ReflectionRetry {

        @Test
        @DisplayName("首次结果为空 → 反思扩大范围 → 第二次成功")
        void emptyResultThenRetrySuccess() {
            // 第一次: 解析参数 → 结果为空
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=10\nSERVICE=order-service\nERROR_LEVEL=ERROR");

            // 第一次查询全部为空
            when(logQueryTool.queryTopErrors(eq(10), eq("order-service"), eq(20)))
                    .thenReturn(SqlExecutionResult.empty(5));
            when(logQueryTool.queryErrorTimeline(eq(10), eq("order-service")))
                    .thenReturn(SqlExecutionResult.empty(3));

            // 反思建议重试
            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("时间范围太短", "扩大到 20 分钟", true));

            // 第二次: 扩大范围后有数据（minutesAgo = 10*2 = 20, serviceName = null）
            when(logQueryTool.queryTopErrors(eq(20), isNull(), eq(20)))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("error_message", "Error", "error_count", 5,
                                      "service_name", "order-service")
                    ), 8));
            when(logQueryTool.queryErrorTimeline(eq(20), isNull()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "error_count", 5)
                    ), 5));

            when(workerModel.generate(contains("错误模式")))
                    .thenReturn("分析结果");

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            assertThat(result.isSuccess()).isTrue();
            verify(reflectionEngine, times(1)).reflect(anyString(), anyString(), anyString(), anyString(), anyString());
            verify(traceStore).recordReflection(eq(SESSION_ID), eq("LOG_ANALYSIS"), anyString());
        }

        @Test
        @DisplayName("查询出错 → 反思不重试 → 返回失败")
        void queryErrorNoRetry() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=30\nSERVICE=ALL\nERROR_LEVEL=ALL");

            when(logQueryTool.queryTopErrors(anyInt(), any(), anyInt()))
                    .thenReturn(SqlExecutionResult.error("connection refused", 0));
            when(logQueryTool.queryErrorTimeline(anyInt(), any()))
                    .thenReturn(SqlExecutionResult.empty(0));

            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("数据库不可用", "基础设施问题", false));

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("日志查询失败");
        }

        @Test
        @DisplayName("三次重试全部为空 → 返回失败")
        void allRetriesFailed() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=10\nSERVICE=ALL\nERROR_LEVEL=ALL");

            when(logQueryTool.queryTopErrors(anyInt(), any(), anyInt()))
                    .thenReturn(SqlExecutionResult.empty(3));
            when(logQueryTool.queryErrorTimeline(anyInt(), any()))
                    .thenReturn(SqlExecutionResult.empty(3));

            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("无数据", "扩大范围", true));

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("3 次尝试");
        }
    }

    // ================================================================
    // 3. Trace 记录验证
    // ================================================================

    @Nested
    @DisplayName("Trace 记录验证")
    class TraceRecording {

        @Test
        @DisplayName("成功场景记录完整链路")
        void fullTraceOnSuccess() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=30\nSERVICE=ALL\nERROR_LEVEL=ALL");
            when(logQueryTool.queryTopErrors(anyInt(), any(), anyInt()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("error_message", "err", "error_count", 1,
                                      "service_name", "svc")
                    ), 5));
            when(logQueryTool.queryErrorTimeline(anyInt(), any()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "error_count", 1)
                    ), 3));
            when(workerModel.generate(contains("错误模式")))
                    .thenReturn("分析完毕");

            agent.execute(TASK, SESSION_ID, QUERY);

            verify(traceStore).recordThought(eq(SESSION_ID), eq("LOG_ANALYSIS"), contains("收到任务"));
            verify(traceStore).recordAction(eq(SESSION_ID), eq("LOG_ANALYSIS"),
                    eq("PARSE_TASK_PARAMS"), anyMap(), anyString(), eq(true));
            verify(traceStore).recordAction(eq(SESSION_ID), eq("LOG_ANALYSIS"),
                    eq("QUERY_TOP_ERRORS"), anyMap(), anyString(), anyBoolean());
            verify(traceStore).recordAction(eq(SESSION_ID), eq("LOG_ANALYSIS"),
                    eq("QUERY_ERROR_TIMELINE"), anyMap(), anyString(), anyBoolean());
            verify(traceStore).recordAction(eq(SESSION_ID), eq("LOG_ANALYSIS"),
                    eq("LLM_ANALYSIS"), anyMap(), anyString(), eq(true));
            verify(traceStore).recordObservation(eq(SESSION_ID), eq("LOG_ANALYSIS"), anyString());
            verify(traceStore).recordDecision(eq(SESSION_ID), eq("LOG_ANALYSIS"), anyString());
        }
    }

    // ================================================================
    // 4. LLM 异常场景
    // ================================================================

    @Nested
    @DisplayName("LLM 异常场景")
    class LlmFailure {

        @Test
        @DisplayName("LLM 解析参数失败 → 使用默认值继续执行")
        void parseParamsFails() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenThrow(new RuntimeException("API error"));

            // 默认参数: 30 分钟, 不过滤服务
            when(logQueryTool.queryTopErrors(eq(30), isNull(), eq(20)))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("error_message", "err", "error_count", 1,
                                      "service_name", "svc")
                    ), 5));
            when(logQueryTool.queryErrorTimeline(eq(30), isNull()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "error_count", 1)
                    ), 3));
            when(workerModel.generate(contains("错误模式")))
                    .thenReturn("分析结果");

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            // 即使参数解析失败，也能用默认值继续
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("LLM 分析失败 → 仍然返回原始数据")
        void analysisFails() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=30\nSERVICE=ALL\nERROR_LEVEL=ALL");
            when(logQueryTool.queryTopErrors(anyInt(), any(), anyInt()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("error_message", "err", "error_count", 1,
                                      "service_name", "svc")
                    ), 5));
            when(logQueryTool.queryErrorTimeline(anyInt(), any()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "error_count", 1)
                    ), 3));

            // LLM 分析调用异常
            when(workerModel.generate(contains("错误模式")))
                    .thenThrow(new RuntimeException("rate limit"));

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            // 分析失败但原始数据仍返回
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData()).contains("LLM 分析失败");
        }
    }

    private Map<String, Object> createRow(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }
}
