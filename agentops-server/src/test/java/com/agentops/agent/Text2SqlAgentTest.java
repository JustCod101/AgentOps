package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import com.agentops.statemachine.ReflectionEngine;
import com.agentops.statemachine.ReflectionEngine.ReflectionResult;
import com.agentops.tool.SandboxSqlExecutor;
import com.agentops.tool.SqlExecutionResult;
import com.agentops.tool.SqlSanitizeResult;
import com.agentops.tool.SqlSanitizer;
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
@DisplayName("Text2SqlAgent 测试")
class Text2SqlAgentTest {

    @Mock private ChatLanguageModel workerModel;
    @Mock private SqlSanitizer sqlSanitizer;
    @Mock private SandboxSqlExecutor sqlExecutor;
    @Mock private ReflectionEngine reflectionEngine;
    @Mock private TraceStore traceStore;

    private Text2SqlAgent agent;

    private static final String SESSION_ID = "test-session-001";
    private static final String ORIGINAL_QUERY = "数据库响应变慢，请帮忙排查";
    private static final String TASK = "查询最近10分钟执行时间超过1秒的慢SQL";

    @BeforeEach
    void setUp() {
        agent = new Text2SqlAgent(workerModel, sqlSanitizer, sqlExecutor, reflectionEngine, traceStore);
    }

    // ================================================================
    // 1. 首次即成功的场景
    // ================================================================

    @Nested
    @DisplayName("首次成功场景")
    class FirstAttemptSuccess {

        @Test
        @DisplayName("LLM 生成有效 SQL → 安全检查通过 → 执行成功返回数据")
        void happyPath() {
            String generatedSql = "SELECT * FROM slow_query_log WHERE execution_time_ms > 1000 LIMIT 20";
            String sanitizedSql = "SELECT * FROM slow_query_log WHERE execution_time_ms > 1000 LIMIT 20";

            // Mock LLM 返回 SQL
            when(workerModel.generate(anyString())).thenReturn(generatedSql);

            // Mock 安全检查通过
            when(sqlSanitizer.sanitize(generatedSql))
                    .thenReturn(SqlSanitizeResult.allowed(sanitizedSql, List.of("slow_query_log")));

            // Mock 执行成功
            List<Map<String, Object>> rows = List.of(
                    createRow("query_text", "SELECT * FROM orders", "execution_time_ms", 3500),
                    createRow("query_text", "SELECT * FROM order_detail", "execution_time_ms", 12000)
            );
            when(sqlExecutor.execute(sanitizedSql, SESSION_ID))
                    .thenReturn(SqlExecutionResult.success(rows, 45));

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAgentName()).isEqualTo("TEXT2SQL");
            assertThat(result.getData()).contains("2 行");
            assertThat(result.getExplanation()).contains("2 行");

            // 验证 TraceStore 被调用（至少记录了 THOUGHT + ACTIONs + OBSERVATION + DECISION）
            verify(traceStore).recordThought(eq(SESSION_ID), eq("TEXT2SQL"), anyString());
            verify(traceStore, atLeastOnce()).recordAction(eq(SESSION_ID), eq("TEXT2SQL"),
                    anyString(), anyMap(), anyString(), anyBoolean());
            verify(traceStore).recordObservation(eq(SESSION_ID), eq("TEXT2SQL"), anyString());
            verify(traceStore).recordDecision(eq(SESSION_ID), eq("TEXT2SQL"), anyString());

            // 不应该触发反思引擎
            verifyNoInteractions(reflectionEngine);
        }

        @Test
        @DisplayName("LLM 输出带 markdown 代码块包裹，自动清理后正常执行")
        void llmOutputWithCodeBlock() {
            String llmOutput = "```sql\nSELECT * FROM slow_query_log LIMIT 10\n```";
            String cleanedSql = "SELECT * FROM slow_query_log LIMIT 10";

            when(workerModel.generate(anyString())).thenReturn(llmOutput);
            when(sqlSanitizer.sanitize(cleanedSql))
                    .thenReturn(SqlSanitizeResult.allowed(cleanedSql, List.of("slow_query_log")));
            when(sqlExecutor.execute(cleanedSql, SESSION_ID))
                    .thenReturn(SqlExecutionResult.success(
                            List.of(createRow("id", 1, "query_text", "test")), 10));

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            assertThat(result.isSuccess()).isTrue();
            // 验证传给 sanitizer 的是清理后的 SQL
            verify(sqlSanitizer).sanitize(cleanedSql);
        }
    }

    // ================================================================
    // 2. 反思重试场景
    // ================================================================

    @Nested
    @DisplayName("反思重试场景")
    class ReflectionRetry {

        @Test
        @DisplayName("首次安全拦截 → 反思建议重试 → 第二次成功")
        void sanitizerRejectThenRetrySuccess() {
            String badSql = "SELECT * FROM users LIMIT 10";
            String goodSql = "SELECT * FROM slow_query_log LIMIT 10";

            // 第一次: LLM 生成了查 users 表的 SQL
            // 第二次: LLM 修正为查 slow_query_log
            when(workerModel.generate(anyString()))
                    .thenReturn(badSql)
                    .thenReturn(goodSql);

            // 第一次: 安全拦截
            when(sqlSanitizer.sanitize(badSql))
                    .thenReturn(SqlSanitizeResult.rejected("禁止访问表: users"));
            // 第二次: 通过
            when(sqlSanitizer.sanitize(goodSql))
                    .thenReturn(SqlSanitizeResult.allowed(goodSql, List.of("slow_query_log")));

            // 反思建议重试
            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("查询了不在白名单的表", "应使用 slow_query_log", true));

            // 第二次执行成功
            when(sqlExecutor.execute(goodSql, SESSION_ID))
                    .thenReturn(SqlExecutionResult.success(
                            List.of(createRow("id", 1, "execution_time_ms", 5000)), 20));

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            assertThat(result.isSuccess()).isTrue();
            verify(reflectionEngine, times(1)).reflect(anyString(), anyString(), anyString(), anyString(), anyString());
            verify(traceStore, times(1)).recordReflection(eq(SESSION_ID), eq("TEXT2SQL"), anyString());
        }

        @Test
        @DisplayName("首次执行出错 → 反思建议重试 → 第二次成功")
        void executionErrorThenRetrySuccess() {
            String sql1 = "SELECT * FROM slow_query_log WHERE execution_time > 1000 LIMIT 10";
            String sql2 = "SELECT * FROM slow_query_log WHERE execution_time_ms > 1000 LIMIT 10";

            when(workerModel.generate(anyString())).thenReturn(sql1).thenReturn(sql2);

            // 两次都通过安全检查
            when(sqlSanitizer.sanitize(sql1))
                    .thenReturn(SqlSanitizeResult.allowed(sql1, List.of("slow_query_log")));
            when(sqlSanitizer.sanitize(sql2))
                    .thenReturn(SqlSanitizeResult.allowed(sql2, List.of("slow_query_log")));

            // 第一次执行报错（列名错误）
            when(sqlExecutor.execute(sql1, SESSION_ID))
                    .thenReturn(SqlExecutionResult.error("column \"execution_time\" does not exist", 5));
            // 第二次成功
            when(sqlExecutor.execute(sql2, SESSION_ID))
                    .thenReturn(SqlExecutionResult.success(
                            List.of(createRow("id", 1, "execution_time_ms", 3000)), 15));

            // 反思建议重试
            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("列名错误", "应使用 execution_time_ms", true));

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            assertThat(result.isSuccess()).isTrue();
            verify(sqlExecutor, times(2)).execute(anyString(), eq(SESSION_ID));
        }

        @Test
        @DisplayName("首次结果为空 → 反思建议扩大范围 → 第二次有数据")
        void emptyResultThenRetryWithData() {
            String sql1 = "SELECT * FROM slow_query_log WHERE execution_time_ms > 10000 LIMIT 10";
            String sql2 = "SELECT * FROM slow_query_log WHERE execution_time_ms > 1000 LIMIT 10";

            when(workerModel.generate(anyString())).thenReturn(sql1).thenReturn(sql2);

            when(sqlSanitizer.sanitize(sql1))
                    .thenReturn(SqlSanitizeResult.allowed(sql1, List.of("slow_query_log")));
            when(sqlSanitizer.sanitize(sql2))
                    .thenReturn(SqlSanitizeResult.allowed(sql2, List.of("slow_query_log")));

            // 第一次: 空结果
            when(sqlExecutor.execute(sql1, SESSION_ID))
                    .thenReturn(SqlExecutionResult.empty(8));
            // 第二次: 有数据
            when(sqlExecutor.execute(sql2, SESSION_ID))
                    .thenReturn(SqlExecutionResult.success(
                            List.of(createRow("id", 1, "execution_time_ms", 2000)), 12));

            // 反思建议降低阈值
            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("阈值太高", "降低 execution_time_ms 阈值到 1000", true));

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData()).contains("1 行");
        }

        @Test
        @DisplayName("三次重试全部失败 → 返回失败结果")
        void allRetriesFailed() {
            String sql = "SELECT * FROM slow_query_log LIMIT 10";

            when(workerModel.generate(anyString())).thenReturn(sql);
            when(sqlSanitizer.sanitize(sql))
                    .thenReturn(SqlSanitizeResult.allowed(sql, List.of("slow_query_log")));
            // 三次都返回空结果
            when(sqlExecutor.execute(sql, SESSION_ID))
                    .thenReturn(SqlExecutionResult.empty(5));

            // 反思引擎每次都建议重试
            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("无数据", "扩大范围", true));

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            // 所有重试用尽
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("3 次尝试");
            verify(workerModel, times(3)).generate(anyString());
        }
    }

    // ================================================================
    // 3. 反思决策场景
    // ================================================================

    @Nested
    @DisplayName("反思决策场景")
    class ReflectionDecision {

        @Test
        @DisplayName("安全拦截后反思建议不重试 → 直接返回失败")
        void reflectionSaysNoRetryOnSanitize() {
            String sql = "DELETE FROM slow_query_log";

            when(workerModel.generate(anyString())).thenReturn(sql);
            when(sqlSanitizer.sanitize(sql))
                    .thenReturn(SqlSanitizeResult.rejected("仅允许 SELECT 查询，当前类型: Delete"));

            // 反思认为不值得重试
            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("生成了 DELETE 语句", "根本性错误", false));

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("不建议重试");
            // 只调用了一次 LLM
            verify(workerModel, times(1)).generate(anyString());
        }

        @Test
        @DisplayName("执行出错后反思建议不重试 → 直接返回失败")
        void reflectionSaysNoRetryOnError() {
            String sql = "SELECT * FROM slow_query_log LIMIT 10";

            when(workerModel.generate(anyString())).thenReturn(sql);
            when(sqlSanitizer.sanitize(sql))
                    .thenReturn(SqlSanitizeResult.allowed(sql, List.of("slow_query_log")));
            when(sqlExecutor.execute(sql, SESSION_ID))
                    .thenReturn(SqlExecutionResult.error("connection refused", 0));

            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("数据库不可用", "基础设施问题，无法通过修改 SQL 解决", false));

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            assertThat(result.isSuccess()).isFalse();
            verify(workerModel, times(1)).generate(anyString());
        }

        @Test
        @DisplayName("空结果后反思建议不重试 → 返回成功但无数据")
        void reflectionSaysNoRetryOnEmpty() {
            String sql = "SELECT * FROM slow_query_log WHERE execution_time_ms > 30000 LIMIT 10";

            when(workerModel.generate(anyString())).thenReturn(sql);
            when(sqlSanitizer.sanitize(sql))
                    .thenReturn(SqlSanitizeResult.allowed(sql, List.of("slow_query_log")));
            when(sqlExecutor.execute(sql, SESSION_ID))
                    .thenReturn(SqlExecutionResult.empty(3));

            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("确实没有这么慢的查询", "数据本身不存在", false));

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            // 空结果 + 不重试 = 成功但无数据
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getExplanation()).contains("无匹配数据");
        }
    }

    // ================================================================
    // 4. LLM 异常场景
    // ================================================================

    @Nested
    @DisplayName("LLM 异常场景")
    class LlmFailure {

        @Test
        @DisplayName("LLM 调用异常 → 直接返回失败")
        void llmThrowsException() {
            when(workerModel.generate(anyString()))
                    .thenThrow(new RuntimeException("API rate limit exceeded"));

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("SQL 失败");
        }

        @Test
        @DisplayName("LLM 返回空内容 → 返回失败")
        void llmReturnsEmpty() {
            when(workerModel.generate(anyString())).thenReturn("   ");

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("LLM 返回非 SQL 文本（被 sanitizer 拦截后重试）")
        void llmReturnsNonSql() {
            // LLM 每次都返回非 SQL
            when(workerModel.generate(anyString()))
                    .thenReturn("I cannot generate SQL for this task.");

            when(sqlSanitizer.sanitize("I cannot generate SQL for this task."))
                    .thenReturn(SqlSanitizeResult.rejected("SQL 语法解析失败"));

            // 反思每次都建议重试
            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("LLM 未生成 SQL", "需要更明确的提示", true));

            AgentResult result = agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            assertThat(result.isSuccess()).isFalse();
        }
    }

    // ================================================================
    // 5. Trace 记录验证
    // ================================================================

    @Nested
    @DisplayName("Trace 记录验证")
    class TraceRecording {

        @Test
        @DisplayName("成功场景记录完整的 Thought → Action → Observation → Decision")
        void fullTraceOnSuccess() {
            String sql = "SELECT * FROM slow_query_log LIMIT 10";

            when(workerModel.generate(anyString())).thenReturn(sql);
            when(sqlSanitizer.sanitize(sql))
                    .thenReturn(SqlSanitizeResult.allowed(sql, List.of("slow_query_log")));
            when(sqlExecutor.execute(sql, SESSION_ID))
                    .thenReturn(SqlExecutionResult.success(
                            List.of(createRow("id", 1, "query_text", "test")), 10));

            agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            // THOUGHT: 初始思考
            verify(traceStore).recordThought(eq(SESSION_ID), eq("TEXT2SQL"), contains("收到任务"));
            // ACTION: LLM 生成 SQL
            verify(traceStore).recordAction(eq(SESSION_ID), eq("TEXT2SQL"),
                    eq("LLM_GENERATE_SQL"), anyMap(), eq(sql), eq(true));
            // ACTION: SQL 安全检查
            verify(traceStore).recordAction(eq(SESSION_ID), eq("TEXT2SQL"),
                    eq("SQL_SANITIZE"), anyMap(), eq(sql), eq(true));
            // ACTION: SQL 执行
            verify(traceStore).recordAction(eq(SESSION_ID), eq("TEXT2SQL"),
                    eq("SQL_EXECUTE"), anyMap(), anyString(), eq(true));
            // OBSERVATION: 执行结果
            verify(traceStore).recordObservation(eq(SESSION_ID), eq("TEXT2SQL"),
                    contains("执行成功"));
            // DECISION: 最终决策
            verify(traceStore).recordDecision(eq(SESSION_ID), eq("TEXT2SQL"),
                    contains("1 行"));
        }

        @Test
        @DisplayName("重试场景记录 REFLECTION")
        void reflectionTraceOnRetry() {
            String sql1 = "SELECT * FROM users LIMIT 10";
            String sql2 = "SELECT * FROM slow_query_log LIMIT 10";

            when(workerModel.generate(anyString())).thenReturn(sql1).thenReturn(sql2);
            when(sqlSanitizer.sanitize(sql1))
                    .thenReturn(SqlSanitizeResult.rejected("禁止访问表: users"));
            when(sqlSanitizer.sanitize(sql2))
                    .thenReturn(SqlSanitizeResult.allowed(sql2, List.of("slow_query_log")));
            when(sqlExecutor.execute(sql2, SESSION_ID))
                    .thenReturn(SqlExecutionResult.success(
                            List.of(createRow("id", 1)), 5));
            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("表名错误", "使用 slow_query_log", true));

            agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            // 验证反思被记录
            verify(traceStore).recordReflection(eq(SESSION_ID), eq("TEXT2SQL"), anyString());
        }
    }

    // ================================================================
    // 6. Prompt 构建验证
    // ================================================================

    @Nested
    @DisplayName("Prompt 构建验证")
    class PromptConstruction {

        @Test
        @DisplayName("重试时 Prompt 包含历史尝试记录")
        void retryPromptContainsHistory() {
            String sql = "SELECT * FROM slow_query_log LIMIT 10";

            when(workerModel.generate(anyString())).thenReturn(sql);
            when(sqlSanitizer.sanitize(sql))
                    .thenReturn(SqlSanitizeResult.allowed(sql, List.of("slow_query_log")));
            // 前两次空，第三次有数据
            when(sqlExecutor.execute(sql, SESSION_ID))
                    .thenReturn(SqlExecutionResult.empty(5))
                    .thenReturn(SqlExecutionResult.empty(5))
                    .thenReturn(SqlExecutionResult.success(
                            List.of(createRow("id", 1)), 5));
            when(reflectionEngine.reflect(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new ReflectionResult("范围太小", "扩大范围", true));

            agent.execute(TASK, SESSION_ID, ORIGINAL_QUERY);

            // 捕获第三次 LLM 调用的 prompt
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(workerModel, times(3)).generate(promptCaptor.capture());

            // 第三次调用应该包含之前的尝试记录
            String thirdPrompt = promptCaptor.getAllValues().get(2);
            assertThat(thirdPrompt).contains("之前的尝试");
            assertThat(thirdPrompt).contains("第 1 次");
            assertThat(thirdPrompt).contains("第 2 次");
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private Map<String, Object> createRow(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }
}
