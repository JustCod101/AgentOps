package com.agentops.statemachine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 状态机 — Act-Observe-Reflect 循环的状态流转引擎
 *
 * 状态流转图:
 *
 *  INIT ──────► ACTING ──────► OBSERVING ──────► REFLECTING
 *                  ▲                                  │
 *                  │          重试（shouldRetry=true） │
 *                  └──────────────────────────────────┘
 *                                                     │
 *                                 shouldRetry=false   │
 *               DONE ◄──── OBSERVING                  ▼
 *              FAILED ◄─── REFLECTING ────► FAILED (达到 maxAttempts)
 *
 * 合法转移:
 * - INIT       → ACTING
 * - ACTING     → OBSERVING
 * - OBSERVING  → REFLECTING (结果不理想)
 * - OBSERVING  → DONE       (结果满意)
 * - REFLECTING → ACTING     (重试)
 * - REFLECTING → FAILED     (放弃)
 * - 任意状态    → FAILED     (异常)
 */
public class AgentStateMachine {

    /**
     * Agent 执行状态
     */
    public enum AgentState {
        /** 初始状态，等待开始 */
        INIT,
        /** 执行中 — 正在调用 LLM / 工具 */
        ACTING,
        /** 观察中 — 检查执行结果 */
        OBSERVING,
        /** 反思中 — 分析失败原因，决定是否重试 */
        REFLECTING,
        /** 完成 — 成功获得满意结果 */
        DONE,
        /** 失败 — 重试耗尽或遇到不可恢复错误 */
        FAILED
    }

    /**
     * 合法的状态转移表
     */
    private static final Map<AgentState, Set<AgentState>> VALID_TRANSITIONS = Map.of(
            AgentState.INIT,       Set.of(AgentState.ACTING, AgentState.FAILED),
            AgentState.ACTING,     Set.of(AgentState.OBSERVING, AgentState.FAILED),
            AgentState.OBSERVING,  Set.of(AgentState.REFLECTING, AgentState.DONE, AgentState.FAILED),
            AgentState.REFLECTING, Set.of(AgentState.ACTING, AgentState.FAILED),
            AgentState.DONE,       Set.of(),
            AgentState.FAILED,     Set.of()
    );

    /**
     * 状态机上下文 — 维护单个 Agent 在单个 Session 中的完整状态
     */
    public static class StateMachineContext {

        private final String sessionId;
        private final String agentName;
        private final int maxAttempts;

        private AgentState currentState;
        private int attemptCount;
        private final List<AttemptRecord> attemptHistory;
        private final Instant createdAt;

        public StateMachineContext(String sessionId, String agentName, int maxAttempts) {
            this.sessionId = sessionId;
            this.agentName = agentName;
            this.maxAttempts = maxAttempts;
            this.currentState = AgentState.INIT;
            this.attemptCount = 0;
            this.attemptHistory = new ArrayList<>();
            this.createdAt = Instant.now();
        }

        public String getSessionId()   { return sessionId; }
        public String getAgentName()    { return agentName; }
        public AgentState getState()    { return currentState; }
        public int getAttemptCount()    { return attemptCount; }
        public int getMaxAttempts()     { return maxAttempts; }
        public Instant getCreatedAt()   { return createdAt; }

        public List<AttemptRecord> getAttemptHistory() {
            return Collections.unmodifiableList(attemptHistory);
        }

        /**
         * 是否还有重试配额
         */
        public boolean hasRemainingAttempts() {
            return attemptCount < maxAttempts;
        }

        /**
         * 是否处于终态
         */
        public boolean isTerminal() {
            return currentState == AgentState.DONE || currentState == AgentState.FAILED;
        }

        /**
         * 增加尝试计数（在 REFLECTING → ACTING 转移时调用）
         */
        void incrementAttempt() {
            this.attemptCount++;
        }

        /**
         * 进入首次 ACTING 时设为第 1 次尝试
         */
        void startFirstAttempt() {
            this.attemptCount = 1;
        }

        void setState(AgentState state) {
            this.currentState = state;
        }

        /**
         * 记录一次尝试
         */
        public void addAttemptRecord(String action, String result) {
            attemptHistory.add(new AttemptRecord(attemptCount, action, result, Instant.now()));
        }

        /**
         * 格式化历史记录（用于反思 Prompt）
         */
        public String formatHistory() {
            if (attemptHistory.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (AttemptRecord r : attemptHistory) {
                sb.append(String.format("- 第 %d 次: 操作=%s → %s\n",
                        r.attempt, r.action, r.result));
            }
            return sb.toString();
        }
    }

    /**
     * 单次尝试记录
     */
    public record AttemptRecord(
            int attempt,
            String action,
            String result,
            Instant timestamp
    ) {}

    // ========================================================================
    // 状态转移
    // ========================================================================

    /**
     * 执行状态转移，校验合法性
     *
     * @param context   状态机上下文
     * @param target    目标状态
     * @throws IllegalStateTransitionException 非法转移
     */
    public static void transitionTo(StateMachineContext context, AgentState target) {
        AgentState current = context.getState();

        // 终态不允许转移
        if (context.isTerminal()) {
            throw new IllegalStateTransitionException(current, target,
                    "状态机已终止（" + current + "），不允许进一步转移");
        }

        // 检查合法性
        Set<AgentState> allowed = VALID_TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw new IllegalStateTransitionException(current, target,
                    "不在合法转移表中，允许: " + (allowed != null ? allowed : "无"));
        }

        // 特殊逻辑: REFLECTING → ACTING 时检查重试配额并递增
        if (current == AgentState.REFLECTING && target == AgentState.ACTING) {
            if (!context.hasRemainingAttempts()) {
                throw new IllegalStateTransitionException(current, target,
                        String.format("已达到最大重试次数 %d", context.getMaxAttempts()));
            }
            context.incrementAttempt();
        }

        // 特殊逻辑: INIT → ACTING 时初始化第 1 次尝试
        if (current == AgentState.INIT && target == AgentState.ACTING) {
            context.startFirstAttempt();
        }

        context.setState(target);
    }

    /**
     * 非法状态转移异常
     */
    public static class IllegalStateTransitionException extends RuntimeException {
        private final AgentState from;
        private final AgentState to;

        public IllegalStateTransitionException(AgentState from, AgentState to, String reason) {
            super(String.format("非法状态转移: %s → %s, 原因: %s", from, to, reason));
            this.from = from;
            this.to = to;
        }

        public AgentState getFrom() { return from; }
        public AgentState getTo()   { return to; }
    }
}
