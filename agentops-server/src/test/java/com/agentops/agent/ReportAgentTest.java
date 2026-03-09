package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import com.agentops.agent.model.DiagnosisResult.DiagnosisReport;
import com.agentops.agent.model.TaskPlan;
import com.agentops.agent.model.TaskPlan.TaskItem;
import com.agentops.trace.TraceStore;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportAgent 测试")
class ReportAgentTest {

    @Mock private ChatLanguageModel routerModel;
    @Mock private TraceStore traceStore;

    private ReportAgent agent;

    private static final String SESSION_ID = "report-test-001";
    private static final String USER_QUERY = "数据库响应变慢，order-service 频繁报错";

    @BeforeEach
    void setUp() {
        agent = new ReportAgent(routerModel, traceStore);
    }

    private TaskPlan createPlan() {
        return TaskPlan.builder()
                .intent("DB_SLOW_QUERY")
                .confidence(0.92)
                .reasoning("用户描述了数据库变慢和服务报错，需要排查慢SQL、日志和指标")
                .tasks(List.of(
                        TaskItem.builder().agent("TEXT2SQL").task("查询慢SQL").priority(1).build(),
                        TaskItem.builder().agent("LOG_ANALYSIS").task("分析错误日志").priority(1).build(),
                        TaskItem.builder().agent("METRIC_QUERY").task("查询系统指标").priority(2).build()
                ))
                .build();
    }

    private Map<String, AgentResult> createAllSuccessResults() {
        Map<String, AgentResult> results = new LinkedHashMap<>();
        results.put("TEXT2SQL", AgentResult.success("TEXT2SQL",
                "| query_text | execution_time_ms |\n| SELECT * FROM order_detail | 12000 |",
                "执行 SQL: SELECT * FROM slow_query_log WHERE execution_time_ms > 1000\n返回 5 行"));
        results.put("LOG_ANALYSIS", AgentResult.success("LOG_ANALYSIS",
                "## 高频错误\nHikariPool-1 - Connection not available: 45 次\n## AI 分析\n连接池耗尽",
                "分析了最近 30 分钟的日志，发现 3 类高频错误"));
        results.put("METRIC_QUERY", AgentResult.success("METRIC_QUERY",
                "## 异常数据点\np99_latency 偏离均值 3.5σ\n## 时间对比\np99_latency +383%",
                "分析了最近 30 分钟的 6 个指标，发现 2 个异常数据点"));
        return results;
    }

    // ================================================================
    // 1. 正常报告生成
    // ================================================================

    @Nested
    @DisplayName("正常报告生成")
    class NormalReport {

        @Test
        @DisplayName("三个 Agent 全部成功 → 生成完整报告")
        void allAgentsSuccess() {
            String llmReport = """
                    # 诊断报告

                    ## 1. 故障概述
                    order-service 数据库连接池耗尽，导致查询超时和服务不可用。

                    ## 2. 根因分析
                    <ROOT_CAUSE>order_detail 表缺失索引导致全表扫描，耗尽连接池</ROOT_CAUSE>

                    ### 证据链
                    - [TEXT2SQL] SELECT * FROM order_detail 执行 12s，扫描 450 万行
                    - [LOG_ANALYSIS] HikariPool-1 连接不可用错误 45 次
                    - [METRIC_QUERY] P99 延迟飙升 383%，偏离均值 3.5σ

                    ### 因果推断
                    缺失索引 → 全表扫描 → 连接占用时间长 → 连接池耗尽 → 级联超时

                    ## 3. 影响范围
                    - **受影响服务**: order-service, payment-service
                    - **受影响接口**: /api/orders, /api/payments
                    - **影响时长**: 约 30 分钟
                    - **严重程度**: P1

                    ## 4. 修复建议
                    1. 🔴 **紧急**: 为 order_detail 表的 order_id 列添加索引
                    2. 🟡 **重要**: 将连接池 maxPoolSize 从 20 调整为 50
                    3. 🟢 **改进**: 优化 order_detail 查询，避免 SELECT *

                    ## 5. 诊断置信度
                    <CONFIDENCE>0.92</CONFIDENCE>
                    三个 Agent 证据一致指向索引缺失。
                    """;

            when(routerModel.generate(anyString())).thenReturn(llmReport);

            DiagnosisReport report = agent.generateReport(
                    USER_QUERY, createPlan(), createAllSuccessResults(), SESSION_ID);

            assertThat(report.getRootCause()).contains("order_detail");
            assertThat(report.getRootCause()).contains("索引");
            assertThat(report.getConfidence()).isGreaterThan(0.9f);
            assertThat(report.getMarkdown()).contains("诊断报告");
            assertThat(report.getMarkdown()).contains("修复建议");
            assertThat(report.getFixSuggestion()).contains("索引");

            // 验证 Trace 记录
            verify(traceStore).recordThought(eq(SESSION_ID), eq("REPORT"), anyString());
            verify(traceStore).recordAction(eq(SESSION_ID), eq("REPORT"),
                    eq("BUILD_REPORT_PROMPT"), anyMap(), anyString(), eq(true));
            verify(traceStore).recordAction(eq(SESSION_ID), eq("REPORT"),
                    eq("LLM_GENERATE_REPORT"), anyMap(), anyString(), eq(true));
            verify(traceStore).recordObservation(eq(SESSION_ID), eq("REPORT"), anyString());
            verify(traceStore).recordDecision(eq(SESSION_ID), eq("REPORT"), anyString());
        }

        @Test
        @DisplayName("部分 Agent 失败 → 报告中注明数据缺失")
        void partialFailure() {
            Map<String, AgentResult> results = new LinkedHashMap<>();
            results.put("TEXT2SQL", AgentResult.success("TEXT2SQL",
                    "慢SQL数据", "查询成功"));
            results.put("LOG_ANALYSIS", AgentResult.failure("LOG_ANALYSIS",
                    "日志查询超时"));
            results.put("METRIC_QUERY", AgentResult.success("METRIC_QUERY",
                    "指标数据", "指标查询成功"));

            String llmReport = """
                    # 诊断报告
                    ## 2. 根因分析
                    <ROOT_CAUSE>慢SQL导致的性能问题（日志数据缺失，降低置信度）</ROOT_CAUSE>
                    ## 4. 修复建议
                    1. 检查慢SQL
                    ## 5. 诊断置信度
                    <CONFIDENCE>0.65</CONFIDENCE>
                    """;

            when(routerModel.generate(anyString())).thenReturn(llmReport);

            DiagnosisReport report = agent.generateReport(
                    USER_QUERY, createPlan(), results, SESSION_ID);

            assertThat(report.getConfidence()).isLessThan(0.7f);

            // 验证 THOUGHT 中记录了失败的 Agent
            ArgumentCaptor<String> thoughtCaptor = ArgumentCaptor.forClass(String.class);
            verify(traceStore).recordThought(eq(SESSION_ID), eq("REPORT"), thoughtCaptor.capture());
            assertThat(thoughtCaptor.getValue()).contains("LOG_ANALYSIS");
        }
    }

    // ================================================================
    // 2. Prompt 构建验证
    // ================================================================

    @Nested
    @DisplayName("Prompt 构建验证")
    class PromptConstruction {

        @Test
        @DisplayName("Prompt 包含用户问题、意图、所有 Agent 结果")
        void promptContainsAllInfo() {
            when(routerModel.generate(anyString())).thenReturn(
                    "<ROOT_CAUSE>test</ROOT_CAUSE>\n<CONFIDENCE>0.5</CONFIDENCE>\n## 4. 修复建议\ntest");

            agent.generateReport(USER_QUERY, createPlan(), createAllSuccessResults(), SESSION_ID);

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(routerModel).generate(promptCaptor.capture());
            String prompt = promptCaptor.getValue();

            // 包含 System Prompt
            assertThat(prompt).contains("资深 SRE");
            // 包含用户问题
            assertThat(prompt).contains(USER_QUERY);
            // 包含意图
            assertThat(prompt).contains("DB_SLOW_QUERY");
            // 包含每个 Agent 的结果
            assertThat(prompt).contains("TEXT2SQL");
            assertThat(prompt).contains("LOG_ANALYSIS");
            assertThat(prompt).contains("METRIC_QUERY");
            // 包含成功/失败标记
            assertThat(prompt).contains("成功");
        }

        @Test
        @DisplayName("Agent 数据过长时自动截断")
        void longDataTruncated() {
            Map<String, AgentResult> results = new LinkedHashMap<>();
            String longData = "x".repeat(5000);
            results.put("TEXT2SQL", AgentResult.success("TEXT2SQL", longData, "概要"));

            when(routerModel.generate(anyString())).thenReturn(
                    "<ROOT_CAUSE>test</ROOT_CAUSE>\n<CONFIDENCE>0.5</CONFIDENCE>\n## 4. 修复建议\ntest");

            agent.generateReport(USER_QUERY, createPlan(), results, SESSION_ID);

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(routerModel).generate(promptCaptor.capture());

            // 确认数据被截断且注明了原始长度
            assertThat(promptCaptor.getValue()).contains("数据已截断");
            assertThat(promptCaptor.getValue()).contains("5000");
        }

        @Test
        @DisplayName("plan 为 null 时不报错")
        void nullPlan() {
            Map<String, AgentResult> results = new LinkedHashMap<>();
            results.put("TEXT2SQL", AgentResult.success("TEXT2SQL", "data", "ok"));

            when(routerModel.generate(anyString())).thenReturn(
                    "<ROOT_CAUSE>test</ROOT_CAUSE>\n<CONFIDENCE>0.5</CONFIDENCE>\n## 4. 修复建议\ntest");

            DiagnosisReport report = agent.generateReport(USER_QUERY, null, results, SESSION_ID);

            assertThat(report).isNotNull();
            assertThat(report.getRootCause()).isEqualTo("test");
        }
    }

    // ================================================================
    // 3. LLM 降级场景
    // ================================================================

    @Nested
    @DisplayName("LLM 降级场景")
    class LlmFallback {

        @Test
        @DisplayName("LLM 调用失败 → 生成降级报告")
        void llmFailsFallbackReport() {
            when(routerModel.generate(anyString()))
                    .thenThrow(new RuntimeException("API rate limit exceeded"));

            DiagnosisReport report = agent.generateReport(
                    USER_QUERY, createPlan(), createAllSuccessResults(), SESSION_ID);

            assertThat(report).isNotNull();
            assertThat(report.getConfidence()).isEqualTo(0.3f);
            assertThat(report.getMarkdown()).contains("降级模式");
            assertThat(report.getMarkdown()).contains(USER_QUERY);
            // 降级报告仍包含原始数据
            assertThat(report.getMarkdown()).contains("TEXT2SQL");
            assertThat(report.getFixSuggestion()).contains("人工分析");

            verify(traceStore).recordAction(eq(SESSION_ID), eq("REPORT"),
                    eq("LLM_GENERATE_REPORT"), anyMap(), isNull(), eq(false));
            verify(traceStore).recordDecision(eq(SESSION_ID), eq("REPORT"),
                    contains("降级"));
        }
    }

    // ================================================================
    // 4. 解析逻辑验证
    // ================================================================

    @Nested
    @DisplayName("解析逻辑验证")
    class ParsingLogic {

        @Test
        @DisplayName("正常解析 ROOT_CAUSE 和 CONFIDENCE 标记")
        void normalParsing() {
            String report = "报告\n<ROOT_CAUSE>连接池耗尽</ROOT_CAUSE>\n"
                    + "<CONFIDENCE>0.88</CONFIDENCE>\n## 4. 修复建议\n加索引";

            when(routerModel.generate(anyString())).thenReturn(report);

            DiagnosisReport result = agent.generateReport(
                    USER_QUERY, createPlan(), createAllSuccessResults(), SESSION_ID);

            assertThat(result.getRootCause()).isEqualTo("连接池耗尽");
            assertThat(result.getConfidence()).isCloseTo(0.88f, org.assertj.core.data.Offset.offset(0.01f));
        }

        @Test
        @DisplayName("缺少标记时使用备选提取逻辑")
        void missingTags() {
            String report = "# 诊断报告\n## 2. 根因分析\n索引缺失导致全表扫描\n"
                    + "## 4. 修复建议\n添加索引\n## 5. 诊断置信度\n约 75%";

            when(routerModel.generate(anyString())).thenReturn(report);

            DiagnosisReport result = agent.generateReport(
                    USER_QUERY, createPlan(), createAllSuccessResults(), SESSION_ID);

            // 备选提取: 根因从"根因分析"章节取第一行
            assertThat(result.getRootCause()).contains("索引缺失");
            // 无 CONFIDENCE 标记，默认 0.5
            assertThat(result.getConfidence()).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("百分比形式置信度自动转换")
        void percentageConfidence() {
            String report = "<ROOT_CAUSE>test</ROOT_CAUSE>\n"
                    + "<CONFIDENCE>85%</CONFIDENCE>\n## 4. 修复建议\nfix";

            when(routerModel.generate(anyString())).thenReturn(report);

            DiagnosisReport result = agent.generateReport(
                    USER_QUERY, createPlan(), createAllSuccessResults(), SESSION_ID);

            assertThat(result.getConfidence()).isCloseTo(0.85f, org.assertj.core.data.Offset.offset(0.01f));
        }

        @Test
        @DisplayName("修复建议提取到下一个章节之前")
        void fixSuggestionExtraction() {
            String report = "前文\n## 4. 修复建议\n1. 加索引\n2. 扩容连接池\n\n## 5. 诊断置信度\n"
                    + "<ROOT_CAUSE>test</ROOT_CAUSE>\n<CONFIDENCE>0.8</CONFIDENCE>";

            when(routerModel.generate(anyString())).thenReturn(report);

            DiagnosisReport result = agent.generateReport(
                    USER_QUERY, createPlan(), createAllSuccessResults(), SESSION_ID);

            assertThat(result.getFixSuggestion()).contains("加索引");
            assertThat(result.getFixSuggestion()).contains("扩容连接池");
            assertThat(result.getFixSuggestion()).doesNotContain("诊断置信度");
        }
    }

    // ================================================================
    // 5. Trace 完整性验证
    // ================================================================

    @Nested
    @DisplayName("Trace 完整性验证")
    class TraceCompleteness {

        @Test
        @DisplayName("THOUGHT 中记录成功/失败的 Agent 列表")
        void thoughtRecordsAgentStatus() {
            Map<String, AgentResult> results = new LinkedHashMap<>();
            results.put("TEXT2SQL", AgentResult.success("TEXT2SQL", "data", "ok"));
            results.put("LOG_ANALYSIS", AgentResult.failure("LOG_ANALYSIS", "timeout"));

            when(routerModel.generate(anyString())).thenReturn(
                    "<ROOT_CAUSE>test</ROOT_CAUSE>\n<CONFIDENCE>0.5</CONFIDENCE>\n## 4. 修复建议\nfix");

            agent.generateReport(USER_QUERY, createPlan(), results, SESSION_ID);

            ArgumentCaptor<String> thoughtCaptor = ArgumentCaptor.forClass(String.class);
            verify(traceStore).recordThought(eq(SESSION_ID), eq("REPORT"), thoughtCaptor.capture());

            String thought = thoughtCaptor.getValue();
            assertThat(thought).contains("TEXT2SQL"); // 成功列表
            assertThat(thought).contains("LOG_ANALYSIS"); // 失败列表
        }

        @Test
        @DisplayName("DECISION 中包含置信度和根因")
        void decisionRecordsKeyInfo() {
            String report = "<ROOT_CAUSE>索引缺失</ROOT_CAUSE>\n"
                    + "<CONFIDENCE>0.9</CONFIDENCE>\n## 4. 修复建议\nfix";

            when(routerModel.generate(anyString())).thenReturn(report);

            agent.generateReport(USER_QUERY, createPlan(), createAllSuccessResults(), SESSION_ID);

            ArgumentCaptor<String> decisionCaptor = ArgumentCaptor.forClass(String.class);
            verify(traceStore).recordDecision(eq(SESSION_ID), eq("REPORT"), decisionCaptor.capture());

            assertThat(decisionCaptor.getValue()).contains("90%");
            assertThat(decisionCaptor.getValue()).contains("索引缺失");
        }
    }
}
