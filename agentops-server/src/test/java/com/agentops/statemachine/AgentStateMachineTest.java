package com.agentops.statemachine;

import com.agentops.statemachine.AgentStateMachine.AgentState;
import com.agentops.statemachine.AgentStateMachine.IllegalStateTransitionException;
import com.agentops.statemachine.AgentStateMachine.StateMachineContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentStateMachine 状态机测试")
class AgentStateMachineTest {

    private static final String SESSION = "sm-test-001";
    private static final String AGENT = "TEXT2SQL";

    // ================================================================
    // 1. 正常状态流转
    // ================================================================

    @Nested
    @DisplayName("正常状态流转")
    class NormalFlow {

        @Test
        @DisplayName("INIT → ACTING → OBSERVING → DONE（首次即成功）")
        void happyPath() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);
            assertThat(ctx.getState()).isEqualTo(AgentState.INIT);

            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            assertThat(ctx.getState()).isEqualTo(AgentState.ACTING);
            assertThat(ctx.getAttemptCount()).isEqualTo(1);

            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            assertThat(ctx.getState()).isEqualTo(AgentState.OBSERVING);

            AgentStateMachine.transitionTo(ctx, AgentState.DONE);
            assertThat(ctx.getState()).isEqualTo(AgentState.DONE);
            assertThat(ctx.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("INIT → ACTING → OBSERVING → REFLECTING → ACTING → OBSERVING → DONE（一次重试后成功）")
        void retryThenSuccess() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);

            // 第 1 次: INIT → ACTING → OBSERVING → REFLECTING
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            assertThat(ctx.getAttemptCount()).isEqualTo(1);

            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            AgentStateMachine.transitionTo(ctx, AgentState.REFLECTING);
            assertThat(ctx.getState()).isEqualTo(AgentState.REFLECTING);

            // 第 2 次: REFLECTING → ACTING → OBSERVING → DONE
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            assertThat(ctx.getAttemptCount()).isEqualTo(2);

            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            AgentStateMachine.transitionTo(ctx, AgentState.DONE);
            assertThat(ctx.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("REFLECTING → FAILED（反思后决定放弃）")
        void reflectThenFail() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);

            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            AgentStateMachine.transitionTo(ctx, AgentState.REFLECTING);
            AgentStateMachine.transitionTo(ctx, AgentState.FAILED);

            assertThat(ctx.getState()).isEqualTo(AgentState.FAILED);
            assertThat(ctx.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("任意状态 → FAILED（异常中断）")
        void failFromActing() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);

            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.FAILED);

            assertThat(ctx.getState()).isEqualTo(AgentState.FAILED);
        }
    }

    // ================================================================
    // 2. 非法状态转移
    // ================================================================

    @Nested
    @DisplayName("非法状态转移")
    class IllegalTransitions {

        @Test
        @DisplayName("INIT → OBSERVING 非法")
        void initToObserving() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);

            assertThatThrownBy(() -> AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("INIT")
                    .hasMessageContaining("OBSERVING");
        }

        @Test
        @DisplayName("INIT → DONE 非法")
        void initToDone() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);

            assertThatThrownBy(() -> AgentStateMachine.transitionTo(ctx, AgentState.DONE))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        @DisplayName("INIT → REFLECTING 非法")
        void initToReflecting() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);

            assertThatThrownBy(() -> AgentStateMachine.transitionTo(ctx, AgentState.REFLECTING))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        @DisplayName("ACTING → DONE 非法（必须先 OBSERVING）")
        void actingToDone() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);

            assertThatThrownBy(() -> AgentStateMachine.transitionTo(ctx, AgentState.DONE))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        @DisplayName("ACTING → REFLECTING 非法（必须先 OBSERVING）")
        void actingToReflecting() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);

            assertThatThrownBy(() -> AgentStateMachine.transitionTo(ctx, AgentState.REFLECTING))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        @DisplayName("OBSERVING → ACTING 非法（必须先 REFLECTING）")
        void observingToActing() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);

            assertThatThrownBy(() -> AgentStateMachine.transitionTo(ctx, AgentState.ACTING))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        @DisplayName("REFLECTING → DONE 非法")
        void reflectingToDone() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            AgentStateMachine.transitionTo(ctx, AgentState.REFLECTING);

            assertThatThrownBy(() -> AgentStateMachine.transitionTo(ctx, AgentState.DONE))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        @DisplayName("DONE → 任何状态 非法（终态不可转移）")
        void doneToAnything() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            AgentStateMachine.transitionTo(ctx, AgentState.DONE);

            assertThatThrownBy(() -> AgentStateMachine.transitionTo(ctx, AgentState.ACTING))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("终止");
        }

        @Test
        @DisplayName("FAILED → 任何状态 非法（终态不可转移）")
        void failedToAnything() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);
            AgentStateMachine.transitionTo(ctx, AgentState.FAILED);

            assertThatThrownBy(() -> AgentStateMachine.transitionTo(ctx, AgentState.ACTING))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("终止");
        }
    }

    // ================================================================
    // 3. 重试配额限制
    // ================================================================

    @Nested
    @DisplayName("重试配额限制")
    class RetryLimits {

        @Test
        @DisplayName("maxAttempts=2 时，第 2 次 REFLECTING → ACTING 成功，第 3 次被拒")
        void maxAttemptsEnforced() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 2);

            // 第 1 次
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            assertThat(ctx.getAttemptCount()).isEqualTo(1);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            AgentStateMachine.transitionTo(ctx, AgentState.REFLECTING);

            // 第 2 次（最后一次机会）
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            assertThat(ctx.getAttemptCount()).isEqualTo(2);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            AgentStateMachine.transitionTo(ctx, AgentState.REFLECTING);

            // 第 3 次被拒（已达上限）
            assertThat(ctx.hasRemainingAttempts()).isFalse();
            assertThatThrownBy(() -> AgentStateMachine.transitionTo(ctx, AgentState.ACTING))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("最大重试次数");
        }

        @Test
        @DisplayName("maxAttempts=1 时，首次失败后无法重试")
        void singleAttempt() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 1);

            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
            AgentStateMachine.transitionTo(ctx, AgentState.OBSERVING);
            AgentStateMachine.transitionTo(ctx, AgentState.REFLECTING);

            assertThat(ctx.hasRemainingAttempts()).isFalse();
            assertThatThrownBy(() -> AgentStateMachine.transitionTo(ctx, AgentState.ACTING))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    // ================================================================
    // 4. 上下文属性
    // ================================================================

    @Nested
    @DisplayName("上下文属性")
    class ContextProperties {

        @Test
        @DisplayName("attemptHistory 记录和格式化")
        void attemptHistory() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);

            ctx.addAttemptRecord("SELECT * FROM users", "安全拦截: 表不在白名单");
            ctx.addAttemptRecord("SELECT * FROM slow_query_log", "结果为空");

            assertThat(ctx.getAttemptHistory()).hasSize(2);
            String formatted = ctx.formatHistory();
            assertThat(formatted).contains("第 1 次");
            assertThat(formatted).contains("SELECT * FROM users");
            assertThat(formatted).contains("安全拦截");
        }

        @Test
        @DisplayName("attemptHistory 不可变（返回只读列表）")
        void attemptHistoryImmutable() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 3);

            assertThatThrownBy(() -> ctx.getAttemptHistory().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("初始状态正确")
        void initialState() {
            StateMachineContext ctx = new StateMachineContext(SESSION, AGENT, 5);

            assertThat(ctx.getSessionId()).isEqualTo(SESSION);
            assertThat(ctx.getAgentName()).isEqualTo(AGENT);
            assertThat(ctx.getState()).isEqualTo(AgentState.INIT);
            assertThat(ctx.getAttemptCount()).isEqualTo(0);
            assertThat(ctx.getMaxAttempts()).isEqualTo(5);
            assertThat(ctx.isTerminal()).isFalse();
            assertThat(ctx.hasRemainingAttempts()).isTrue();
            assertThat(ctx.getCreatedAt()).isNotNull();
        }
    }
}
