package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import com.agentops.statemachine.ReflectionEngine;
import com.agentops.statemachine.ReflectionEngine.ReflectionResult;
import com.agentops.tool.MetricQueryTool;
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
@DisplayName("MetricQueryAgent 测试")
class MetricQueryAgentTest {

    @Mock private ChatLanguageModel workerModel;
    @Mock private MetricQueryTool metricQueryTool;
    @Mock private ReflectionEngine reflectionEngine;
    @Mock private TraceStore traceStore;

    private MetricQueryAgent agent;

    private static final String SESSION_ID = "metric-test-001";
    private static final String QUERY = "数据库响应变慢，请排查系统指标";
    private static final String TASK = "查询最近 30 分钟的 CPU、QPS 和 P99 延迟趋势";

    @BeforeEach
    void setUp() {
        agent = new MetricQueryAgent(workerModel, metricQueryTool, reflectionEngine, traceStore);
    }

    // ================================================================
    // 1. 首次成功场景
    // ================================================================

    @Nested
    @DisplayName("首次成功场景")
    class FirstAttemptSuccess {

        @Test
        @DisplayName("查询趋势+异常+对比 → LLM 分析 → 成功")
        void happyPath() {
            // LLM 解析参数
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=30\nMETRICS=cpu_usage,qps,p99_latency\nSERVICE=ALL");

            // 趋势查询
            when(metricQueryTool.queryTrend(eq("cpu_usage"), eq(30), isNull()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "avg_value", 45.5, "max_value", 78.2)
                    ), 10));
            when(metricQueryTool.queryTrend(eq("qps"), eq(30), isNull()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "avg_value", 1200.0, "max_value", 1850.0)
                    ), 8));
            when(metricQueryTool.queryTrend(eq("p99_latency"), eq(30), isNull()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "avg_value", 350.0, "max_value", 1200.0)
                    ), 12));

            // 异常检测
            when(metricQueryTool.detectAnomalies(30))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("metric_name", "p99_latency", "metric_value", 1200.0,
                                      "avg_value", 350.0, "deviation_sigma", 3.5)
                    ), 5));

            // 时间对比
            when(metricQueryTool.compareWithPrevious(30))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("metric_name", "p99_latency", "current_avg", 580.0,
                                      "previous_avg", 120.0, "change_pct", 383.33)
                    ), 7));

            // LLM 分析
            when(workerModel.generate(contains("指标概览")))
                    .thenReturn("**异常指标**: P99 延迟飙升至 1200ms，偏离均值 3.5σ\n**结论**: 数据库连接瓶颈");

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAgentName()).isEqualTo("METRIC_QUERY");
            assertThat(result.getData()).contains("指标趋势");
            assertThat(result.getData()).contains("异常数据点");
            assertThat(result.getData()).contains("时间对比");
            assertThat(result.getData()).contains("AI 分析结论");
            assertThat(result.getExplanation()).contains("3 个指标");
            assertThat(result.getExplanation()).contains("1 个异常数据点");

            verify(traceStore).recordThought(eq(SESSION_ID), eq("METRIC_QUERY"), anyString());
            verify(traceStore).recordDecision(eq(SESSION_ID), eq("METRIC_QUERY"), anyString());
            verifyNoInteractions(reflectionEngine);
        }

        @Test
        @DisplayName("无异常数据点 → 仍然成功返回趋势和对比")
        void noAnomalies() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=30\nMETRICS=cpu_usage\nSERVICE=ALL");

            when(metricQueryTool.queryTrend(eq("cpu_usage"), eq(30), isNull()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "avg_value", 30.0)
                    ), 5));
            when(metricQueryTool.detectAnomalies(30))
                    .thenReturn(SqlExecutionResult.empty(3));
            when(metricQueryTool.compareWithPrevious(30))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("metric_name", "cpu_usage", "current_avg", 30.0,
                                      "previous_avg", 28.0, "change_pct", 7.14)
                    ), 4));

            when(workerModel.generate(contains("指标概览")))
                    .thenReturn("系统正常");

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getExplanation()).contains("0 个异常");
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
        void emptyThenRetrySuccess() {
            // 第一次参数
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=5\nMETRICS=cpu_usage\nSERVICE=my-service");

            // 第一次: 全部为空
            when(metricQueryTool.queryTrend(eq("cpu_usage"), eq(5), eq("my-service")))
                    .thenReturn(SqlExecutionResult.empty(3));
            when(metricQueryTool.detectAnomalies(5))
                    .thenReturn(SqlExecutionResult.empty(2));
            when(metricQueryTool.compareWithPrevious(5))
                    .thenReturn(SqlExecutionResult.empty(2));

            // 反思
            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("时间太短", "扩大到 10 分钟", true));

            // 第二次: 扩大到所有指标 (5*2=10 分钟, ALL_METRICS, service=null)
            // 需要 mock 所有 6 个指标的 queryTrend
            when(metricQueryTool.queryTrend(anyString(), eq(10), isNull()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "avg_value", 50.0)
                    ), 5));
            when(metricQueryTool.detectAnomalies(10))
                    .thenReturn(SqlExecutionResult.empty(3));
            when(metricQueryTool.compareWithPrevious(10))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("metric_name", "cpu_usage", "current_avg", 50.0,
                                      "previous_avg", 40.0, "change_pct", 25.0)
                    ), 4));

            when(workerModel.generate(contains("指标概览")))
                    .thenReturn("CPU 略有上升");

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            assertThat(result.isSuccess()).isTrue();
            verify(reflectionEngine, times(1)).reflect(anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("查询出错 → 反思不重试 → 返回失败")
        void queryErrorNoRetry() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=30\nMETRICS=cpu_usage\nSERVICE=ALL");

            when(metricQueryTool.queryTrend(anyString(), anyInt(), any()))
                    .thenReturn(SqlExecutionResult.error("connection refused", 0));
            when(metricQueryTool.detectAnomalies(anyInt()))
                    .thenReturn(SqlExecutionResult.error("connection refused", 0));
            when(metricQueryTool.compareWithPrevious(anyInt()))
                    .thenReturn(SqlExecutionResult.error("connection refused", 0));

            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("数据库不可用", "无法修复", false));

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("指标查询失败");
        }

        @Test
        @DisplayName("三次全空 → 返回失败")
        void allRetriesFailed() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=10\nMETRICS=cpu_usage\nSERVICE=ALL");

            when(metricQueryTool.queryTrend(anyString(), anyInt(), any()))
                    .thenReturn(SqlExecutionResult.empty(2));
            when(metricQueryTool.detectAnomalies(anyInt()))
                    .thenReturn(SqlExecutionResult.empty(2));
            when(metricQueryTool.compareWithPrevious(anyInt()))
                    .thenReturn(SqlExecutionResult.empty(2));

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
        @DisplayName("成功场景记录完整链路: THOUGHT → ACTIONs → OBSERVATION → DECISION")
        void fullTraceOnSuccess() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=30\nMETRICS=cpu_usage\nSERVICE=ALL");

            when(metricQueryTool.queryTrend(anyString(), anyInt(), any()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "avg_value", 50.0)
                    ), 5));
            when(metricQueryTool.detectAnomalies(anyInt()))
                    .thenReturn(SqlExecutionResult.empty(3));
            when(metricQueryTool.compareWithPrevious(anyInt()))
                    .thenReturn(SqlExecutionResult.empty(2));

            when(workerModel.generate(contains("指标概览")))
                    .thenReturn("正常");

            agent.execute(TASK, SESSION_ID, QUERY);

            verify(traceStore).recordThought(eq(SESSION_ID), eq("METRIC_QUERY"), contains("收到任务"));
            verify(traceStore).recordAction(eq(SESSION_ID), eq("METRIC_QUERY"),
                    eq("PARSE_TASK_PARAMS"), anyMap(), anyString(), eq(true));
            verify(traceStore).recordAction(eq(SESSION_ID), eq("METRIC_QUERY"),
                    eq("QUERY_TREND_CPU_USAGE"), anyMap(), anyString(), anyBoolean());
            verify(traceStore).recordAction(eq(SESSION_ID), eq("METRIC_QUERY"),
                    eq("DETECT_ANOMALIES"), anyMap(), anyString(), anyBoolean());
            verify(traceStore).recordAction(eq(SESSION_ID), eq("METRIC_QUERY"),
                    eq("COMPARE_WITH_PREVIOUS"), anyMap(), anyString(), anyBoolean());
            verify(traceStore).recordAction(eq(SESSION_ID), eq("METRIC_QUERY"),
                    eq("LLM_ANALYSIS"), anyMap(), anyString(), eq(true));
            verify(traceStore).recordObservation(eq(SESSION_ID), eq("METRIC_QUERY"), anyString());
            verify(traceStore).recordDecision(eq(SESSION_ID), eq("METRIC_QUERY"), anyString());
        }
    }

    // ================================================================
    // 4. LLM 异常场景
    // ================================================================

    @Nested
    @DisplayName("LLM 异常场景")
    class LlmFailure {

        @Test
        @DisplayName("LLM 解析参数失败 → 使用默认值（全指标30分钟）继续")
        void parseParamsFails() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenThrow(new RuntimeException("timeout"));

            // 默认: 30 分钟，所有 6 个指标
            when(metricQueryTool.queryTrend(anyString(), eq(30), isNull()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "avg_value", 50.0)
                    ), 5));
            when(metricQueryTool.detectAnomalies(30))
                    .thenReturn(SqlExecutionResult.empty(3));
            when(metricQueryTool.compareWithPrevious(30))
                    .thenReturn(SqlExecutionResult.empty(2));

            when(workerModel.generate(contains("指标概览")))
                    .thenReturn("正常");

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

            assertThat(result.isSuccess()).isTrue();
            // 验证所有 6 个指标都被查询
            verify(metricQueryTool, times(6)).queryTrend(anyString(), eq(30), isNull());
        }

        @Test
        @DisplayName("LLM 分析失败 → 仍返回原始数据")
        void analysisFails() {
            when(workerModel.generate(contains("提取查询参数")))
                    .thenReturn("MINUTES=30\nMETRICS=cpu_usage\nSERVICE=ALL");

            when(metricQueryTool.queryTrend(anyString(), anyInt(), any()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("time_bucket", "10:00", "avg_value", 90.0)
                    ), 5));
            when(metricQueryTool.detectAnomalies(anyInt()))
                    .thenReturn(SqlExecutionResult.success(List.of(
                            createRow("metric_name", "cpu_usage", "deviation_sigma", 4.2)
                    ), 3));
            when(metricQueryTool.compareWithPrevious(anyInt()))
                    .thenReturn(SqlExecutionResult.empty(2));

            when(workerModel.generate(contains("指标概览")))
                    .thenThrow(new RuntimeException("rate limit"));

            AgentResult result = agent.execute(TASK, SESSION_ID, QUERY);

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
