package com.agentops.statemachine;

import com.agentops.statemachine.AgentStateMachine.AgentState;
import com.agentops.statemachine.AgentStateMachine.IllegalStateTransitionException;
import com.agentops.statemachine.AgentStateMachine.StateMachineContext;
import com.agentops.statemachine.ReflectionEngine.ReflectionResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionEngine 反思引擎测试")
class ReflectionEngineTest {

    @Mock private ChatLanguageModel workerModel;

    private ReflectionEngine engine;

    private static final String SESSION_ID = "reflect-test-001";
    private static final String AGENT_NAME = "TEXT2SQL";
    private static final String TASK = "查询最近10分钟的慢SQL";
    private static final String QUERY = "数据库响应变慢";

    @BeforeEach
    void setUp() {
        engine = new ReflectionEngine(workerModel);
    }

    // ================================================================
    // 1. 基础 reflect() 方法（兼容原有调用方式）
    // ================================================================

    @Nested
    @DisplayName("基础 reflect() 方法")
    class BasicReflect {

        @Test
        @DisplayName("LLM 返回建议重试 → shouldRetry=true")
        void shouldRetry() {
            when(workerModel.generate(anyString())).thenReturn(
                    "1. **失败原因**: 列名拼写错误\n"
                    + "2. **修正建议**: 将 execution_time 改为 execution_time_ms\n"
                    + "3. **是否重试**: YES");

            ReflectionResult result = engine.reflect(
                    TASK, QUERY,
                    "SELECT * FROM slow_query_log WHERE execution_time > 1000",
                    "column \"execution_time\" does not exist",
                    "");

            assertThat(result.shouldRetry()).isTrue();
            assertThat(result.failureReason()).contains("列名");
            assertThat(result.suggestion()).contains("execution_time_ms");
        }

        @Test
        @DisplayName("LLM 返回不重试 → shouldRetry=false")
        void shouldNotRetry() {
            when(workerModel.generate(anyString())).thenReturn(
                    "1. **失败原因**: 数据库连接不可用\n"
                    + "2. **修正建议**: 等待数据库恢复\n"
                    + "3. **是否重试**: NO");

            ReflectionResult result = engine.reflect(
                    TASK, QUERY,
                    "SELECT * FROM slow_query_log",
                    "connection refused",
                    "");

            assertThat(result.shouldRetry()).isFalse();
        }

        @Test
        @DisplayName("LLM 调用异常 → 默认不重试")
        void llmException() {
            when(workerModel.generate(anyString()))
                    .thenThrow(new RuntimeException("rate limit"));

            ReflectionResult result = engine.reflect(TASK, QUERY, "sql", "error", "");

            assertThat(result.shouldRetry()).isFalse();
            assertThat(result.failureReason()).contains("调用失败");
        }

        @Test
        @DisplayName("Prompt 包含历史尝试记录")
        void promptContainsHistory() {
            when(workerModel.generate(anyString())).thenReturn(
                    "**失败原因**: test\n**修正建议**: test\n**是否重试**: NO");

            String history = "- 第 1 次: SQL=`SELECT * FROM users` → 安全拦截\n";
            engine.reflect(TASK, QUERY, "SELECT * FROM users", "rejected", history);

            verify(workerModel).generate(contains("第 1 次"));
            verify(workerModel).generate(contains("SELECT * FROM users"));
        }

        @Test
        @DisplayName("Prompt 包含当前尝试次数")
        void promptContainsAttemptCount() {
            when(workerModel.generate(anyString())).thenReturn(
                    "**失败原因**: test\n**修正建议**: test\n**是否重试**: NO");

            String history = "- 第 1 次: x → y\n- 第 2 次: a → b\n";
            engine.reflect(TASK, QUERY, "sql", "error", history);

            // 有 2 条历史，当前应该是第 3 次
            verify(workerModel).generate(contains("第 3 次"));
        }

        @Test
        @DisplayName("ReflectionResult.toPromptText() 格式正确")
        void toPromptText() {
            ReflectionResult result = new ReflectionResult("列名错误", "用 execution_time_ms", true);

            String text = result.toPromptText();
            assertThat(text).contains("反思: 列名错误");
            assertThat(text).contains("建议: 用 execution_time_ms");
            assertThat(text).contains("是否重试: YES");
        }
    }

    // ================================================================
    // 2. 上下文管理
    // ================================================================

    @Nested
    @DisplayName("上下文管理")
    class ContextManagement {

        @Test
        @DisplayName("getOrCreateContext 首次创建，后续返回同一实例")
        void getOrCreateReturnsExisting() {
            StateMachineContext ctx1 = engine.getOrCreateContext(SESSION_ID, AGENT_NAME, 3);
            StateMachineContext ctx2 = engine.getOrCreateContext(SESSION_ID, AGENT_NAME, 5);

            assertThat(ctx1).isSameAs(ctx2);
            // maxAttempts 保持首次创建的值
            assertThat(ctx2.getMaxAttempts()).isEqualTo(3);
        }

        @Test
        @DisplayName("不同 session 或 agent 获得独立上下文")
        void independentContexts() {
            StateMachineContext ctx1 = engine.getOrCreateContext("s1", "A", 3);
            StateMachineContext ctx2 = engine.getOrCreateContext("s1", "B", 3);
            StateMachineContext ctx3 = engine.getOrCreateContext("s2", "A", 3);

            assertThat(ctx1).isNotSameAs(ctx2);
            assertThat(ctx1).isNotSameAs(ctx3);
        }

        @Test
        @DisplayName("getContext 不存在时返回 null")
        void getContextNull() {
            assertThat(engine.getContext("nonexistent", "agent")).isNull();
        }

        @Test
        @DisplayName("transitionTo 驱动指定上下文的状态转移")
        void transitionToByKey() {
            engine.getOrCreateContext(SESSION_ID, AGENT_NAME, 3);

            engine.transitionTo(SESSION_ID, AGENT_NAME, AgentState.ACTING);

            StateMachineContext ctx = engine.getContext(SESSION_ID, AGENT_NAME);
            assertThat(ctx.getState()).isEqualTo(AgentState.ACTING);
        }

        @Test
        @DisplayName("cleanupSession 清理所有该 session 的上下文")
        void cleanupSession() {
            engine.getOrCreateContext(SESSION_ID, "AGENT_A", 3);
            engine.getOrCreateContext(SESSION_ID, "AGENT_B", 3);
            engine.getOrCreateContext("other-session", "AGENT_A", 3);

            engine.cleanupSession(SESSION_ID);

            assertThat(engine.getContext(SESSION_ID, "AGENT_A")).isNull();
            assertThat(engine.getContext(SESSION_ID, "AGENT_B")).isNull();
            assertThat(engine.getContext("other-session", "AGENT_A")).isNotNull();
        }

        @Test
        @DisplayName("cleanupContext 只清理指定的上下文")
        void cleanupSpecificContext() {
            engine.getOrCreateContext(SESSION_ID, "A", 3);
            engine.getOrCreateContext(SESSION_ID, "B", 3);

            engine.cleanupContext(SESSION_ID, "A");

            assertThat(engine.getContext(SESSION_ID, "A")).isNull();
            assertThat(engine.getContext(SESSION_ID, "B")).isNotNull();
        }
    }

    // ================================================================
    // 3. reflectWithContext（状态机集成反思）
    // ================================================================

    @Nested
    @DisplayName("reflectWithContext 带状态机的反思")
    class ReflectWithContext {

        @Test
        @DisplayName("反思建议重试 + 有配额 → 状态转移到 ACTING")
        void retryWithQuota() {
            when(workerModel.generate(anyString())).thenReturn(
                    "**失败原因**: 范围太小\n**修正建议**: 扩大\n**是否重试**: YES");

            StateMachineContext ctx = engine.getOrCreateContext(SESSION_ID, AGENT_NAME, 3);
            // 推进到 OBSERVING（模拟正常流程）
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);

            ReflectionResult result = engine.reflectWithContext(
                    ctx, TASK, QUERY, "SELECT * FROM slow_query_log", "结果为空");

            assertThat(result.shouldRetry()).isTrue();
            assertThat(ctx.getState()).isEqualTo(AgentState.ACTING);
            assertThat(ctx.getAttemptCount()).isEqualTo(2);
            assertThat(ctx.getAttemptHistory()).hasSize(1);
        }

        @Test
        @DisplayName("反思建议不重试 → 状态转移到 FAILED")
        void noRetryTransitionsToFailed() {
            when(workerModel.generate(anyString())).thenReturn(
                    "**失败原因**: 根本性错误\n**修正建议**: 无\n**是否重试**: NO");

            StateMachineContext ctx = engine.getOrCreateContext(SESSION_ID, AGENT_NAME, 3);
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);

            ReflectionResult result = engine.reflectWithContext(
                    ctx, TASK, QUERY, "sql", "table not found");

            assertThat(result.shouldRetry()).isFalse();
            assertThat(ctx.getState()).isEqualTo(AgentState.FAILED);
            assertThat(ctx.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("反思建议重试但无配额 → 状态转移到 FAILED")
        void retryButNoQuota() {
            when(workerModel.generate(anyString())).thenReturn(
                    "**失败原因**: 还能修\n**修正建议**: 换个条件\n**是否重试**: YES");

            StateMachineContext ctx = engine.getOrCreateContext(SESSION_ID, AGENT_NAME, 1);
            // 已经用完第 1 次
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);

            ReflectionResult result = engine.reflectWithContext(
                    ctx, TASK, QUERY, "sql", "empty");

            assertThat(result.shouldRetry()).isTrue();
            // 虽然 shouldRetry=true，但无配额，强制 FAILED
            assertThat(ctx.getState()).isEqualTo(AgentState.FAILED);
        }

        @Test
        @DisplayName("多次反思重试的完整循环")
        void fullRetryLoop() {
            // 前两次建议重试，第三次建议放弃
            when(workerModel.generate(anyString()))
                    .thenReturn("**失败原因**: r1\n**修正建议**: s1\n**是否重试**: YES")
                    .thenReturn("**失败原因**: r2\n**修正建议**: s2\n**是否重试**: YES")
                    .thenReturn("**失败原因**: r3\n**修正建议**: s3\n**是否重试**: NO");

            StateMachineContext ctx = engine.getOrCreateContext(SESSION_ID, AGENT_NAME, 5);

            // 第 1 次
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            engine.reflectWithContext(ctx, TASK, QUERY, "sql1", "fail1");
            assertThat(ctx.getState()).isEqualTo(AgentState.ACTING);
            assertThat(ctx.getAttemptCount()).isEqualTo(2);

            // 第 2 次
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            engine.reflectWithContext(ctx, TASK, QUERY, "sql2", "fail2");
            assertThat(ctx.getState()).isEqualTo(AgentState.ACTING);
            assertThat(ctx.getAttemptCount()).isEqualTo(3);

            // 第 3 次
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            engine.reflectWithContext(ctx, TASK, QUERY, "sql3", "fail3");
            assertThat(ctx.getState()).isEqualTo(AgentState.FAILED);

            // 验证 3 条历史记录
            assertThat(ctx.getAttemptHistory()).hasSize(3);

            String history = ctx.formatHistory();
            assertThat(history).contains("sql1");
            assertThat(history).contains("sql2");
            assertThat(history).contains("sql3");
        }

        @Test
        @DisplayName("从 OBSERVING 自动转移到 REFLECTING")
        void autoTransitionToReflecting() {
            when(workerModel.generate(anyString())).thenReturn(
                    "**失败原因**: test\n**修正建议**: test\n**是否重试**: NO");

            StateMachineContext ctx = engine.getOrCreateContext(SESSION_ID, AGENT_NAME, 3);
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);

            // reflectWithContext 应自动从 OBSERVING → REFLECTING
            engine.reflectWithContext(ctx, TASK, QUERY, "sql", "error");

            // 最终因 shouldRetry=false 而变 FAILED
            assertThat(ctx.getState()).isEqualTo(AgentState.FAILED);
        }

        @Test
        @DisplayName("已在 REFLECTING 态时不重复转移")
        void alreadyReflecting() {
            when(workerModel.generate(anyString())).thenReturn(
                    "**失败原因**: test\n**修正建议**: test\n**是否重试**: NO");

            StateMachineContext ctx = engine.getOrCreateContext(SESSION_ID, AGENT_NAME, 3);
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            AgentStateMachine.transitionTo(ctx, AgentState.REFLECTING);

            // 已在 REFLECTING 态，直接反思
            engine.reflectWithContext(ctx, TASK, QUERY, "sql", "error");

            assertThat(ctx.getState()).isEqualTo(AgentState.FAILED);
        }
    }

    // ================================================================
    // 4. 解析逻辑边界情况
    // ================================================================

    @Nested
    @DisplayName("解析逻辑边界情况")
    class ParsingEdgeCases {

        @Test
        @DisplayName("LLM 输出格式不标准 — 缺少章节标记")
        void nonStandardFormat() {
            when(workerModel.generate(anyString())).thenReturn(
                    "列名写错了，应该用 execution_time_ms。建议重试。YES。");

            ReflectionResult result = engine.reflect(TASK, QUERY, "sql", "error", "");

            // 无法提取到结构化信息
            assertThat(result.failureReason()).contains("未提取到");
            // 没有"是否重试"标记，默认 false
            assertThat(result.shouldRetry()).isFalse();
        }

        @Test
        @DisplayName("LLM 输出中 YES 在 NO 前面 → shouldRetry=true")
        void yesBeforeNo() {
            when(workerModel.generate(anyString())).thenReturn(
                    "**是否重试**: YES, this is NOT a permanent error");

            ReflectionResult result = engine.reflect(TASK, QUERY, "sql", "error", "");

            assertThat(result.shouldRetry()).isTrue();
        }

        @Test
        @DisplayName("LLM 输出中只有 NO → shouldRetry=false")
        void onlyNo() {
            when(workerModel.generate(anyString())).thenReturn(
                    "**失败原因**: 表不存在\n**修正建议**: 无\n**是否重试**: NO");

            ReflectionResult result = engine.reflect(TASK, QUERY, "sql", "error", "");

            assertThat(result.shouldRetry()).isFalse();
        }

        @Test
        @DisplayName("空历史记录 → Prompt 包含'无（首次尝试）'")
        void emptyHistory() {
            when(workerModel.generate(anyString())).thenReturn(
                    "**失败原因**: test\n**修正建议**: test\n**是否重试**: NO");

            engine.reflect(TASK, QUERY, "sql", "error", "");

            verify(workerModel).generate(contains("无（首次尝试）"));
        }
    }
}
