package com.agentops.statemachine;

import com.agentops.statemachine.AgentStateMachine.AgentState;
import com.agentops.statemachine.AgentStateMachine.StateMachineContext;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 反思引擎 — Act-Observe-Reflect 自纠错机制的核心
 *
 * 整合 AgentStateMachine 提供完整的状态管理:
 * 1. 按 session + agent 维护独立的状态机上下文（ConcurrentMap）
 * 2. reflect() 在调用 LLM 前后自动驱动状态转移
 * 3. 反思结果结构化输出: 失败原因 + 修正建议 + 是否重试
 *
 * 状态流转:
 *   INIT → ACTING → OBSERVING → REFLECTING → ACTING (重试)
 *                                           → FAILED (放弃)
 *                  → DONE (结果满意)
 */
@Service
public class ReflectionEngine {

    private static final Logger log = LoggerFactory.getLogger(ReflectionEngine.class);

    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final ChatLanguageModel workerModel;

    /**
     * 按 "sessionId::agentName" 维护独立状态机上下文
     *
     * 每个 Agent 在每个 Session 中有独立的状态、重试计数和历史记录，
     * 互不干扰。ConcurrentHashMap 保证多 Agent 并行时线程安全。
     */
    private final ConcurrentHashMap<String, StateMachineContext> contexts = new ConcurrentHashMap<>();

    /**
     * 反思 Prompt 模板
     */
    private static final String REFLECTION_PROMPT_TEMPLATE = """
            你是一个数据库查询调试专家。请分析以下查询/操作失败的原因，并给出修正建议。

            ## 原始任务
            %s

            ## 用户原始问题
            %s

            ## 执行的操作
            ```
            %s
            ```

            ## 执行结果
            %s

            ## 可用的表结构
            - slow_query_log: id, query_text, execution_time_ms, database_name, user_name, rows_examined, rows_sent, created_at
            - db_connection_status: id, total_connections, active_connections, idle_connections, waiting_connections, max_connections, pool_name, created_at
            - service_error_log: id, service_name, error_level, error_message, stack_trace, request_path, request_method, response_code, trace_id, created_at
            - system_metric: id, metric_name, metric_value, metric_unit, host, service_name, created_at

            ## 历史尝试记录
            %s

            ## 当前尝试次数
            第 %d 次（最多 %d 次）

            ## 请输出以下内容（纯文本，不要 JSON）：
            1. **失败原因**: 一句话总结为什么查询失败或结果为空
            2. **修正建议**: 具体的修改方向（如：扩大时间范围、使用正确的列名、调整 WHERE 条件等）
            3. **是否重试**: YES 或 NO（如果错误是根本性的，比如表不存在、数据库不可用，则 NO）
            """;

    public ReflectionEngine(@Qualifier("workerModel") ChatLanguageModel workerModel) {
        this.workerModel = workerModel;
    }

    // ========================================================================
    // 状态机上下文管理
    // ========================================================================

    /**
     * 获取或创建状态机上下文
     *
     * @param sessionId   诊断会话 ID
     * @param agentName   Agent 名称
     * @param maxAttempts 最大重试次数
     * @return 状态机上下文（已存在则返回现有，否则新建）
     */
    public StateMachineContext getOrCreateContext(String sessionId, String agentName, int maxAttempts) {
        String key = buildKey(sessionId, agentName);
        return contexts.computeIfAbsent(key,
                k -> new StateMachineContext(sessionId, agentName, maxAttempts));
    }

    /**
     * 使用默认最大重试次数获取上下文
     */
    public StateMachineContext getOrCreateContext(String sessionId, String agentName) {
        return getOrCreateContext(sessionId, agentName, DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * 获取现有上下文（不创建）
     *
     * @return 上下文，如果不存在返回 null
     */
    public StateMachineContext getContext(String sessionId, String agentName) {
        return contexts.get(buildKey(sessionId, agentName));
    }

    /**
     * 驱动状态转移
     *
     * @param sessionId  会话 ID
     * @param agentName  Agent 名称
     * @param target     目标状态
     * @throws AgentStateMachine.IllegalStateTransitionException 非法转移
     */
    public void transitionTo(String sessionId, String agentName, AgentState target) {
        StateMachineContext ctx = getOrCreateContext(sessionId, agentName);
        AgentState before = ctx.getState();

        AgentStateMachine.transitionTo(ctx, target);

        log.debug("状态转移 [session={}, agent={}]: {} → {}",
                sessionId, agentName, before, target);
    }

    /**
     * 清理会话相关的所有状态机上下文
     */
    public void cleanupSession(String sessionId) {
        contexts.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + "::"));
        log.debug("清理会话状态机上下文: sessionId={}", sessionId);
    }

    /**
     * 清理指定的状态机上下文
     */
    public void cleanupContext(String sessionId, String agentName) {
        contexts.remove(buildKey(sessionId, agentName));
    }

    // ========================================================================
    // 反思分析（保持与所有 Agent 的调用兼容）
    // ========================================================================

    /**
     * 执行反思分析
     *
     * 此方法是所有 Worker Agent 调用反思引擎的统一入口。
     * 自动管理状态转移: 确保处于 REFLECTING 态（或自动推进到该态），
     * 调用 LLM 分析后返回结构化结果。
     *
     * @param task           子任务描述
     * @param originalQuery  用户原始问题
     * @param generatedSql   本轮生成的 SQL 或执行的操作描述
     * @param executionResult 执行结果（成功但为空）或错误信息
     * @param attemptHistory 历史尝试摘要（前几轮的操作 + 结果）
     * @return 结构化反思结果
     */
    public ReflectionResult reflect(String task, String originalQuery,
                                     String generatedSql, String executionResult,
                                     String attemptHistory) {
        log.debug("开始反思分析: task={}, action={}", task, generatedSql);

        // 从 attemptHistory 推断当前尝试次数
        int currentAttempt = countAttempts(attemptHistory);

        String prompt = String.format(REFLECTION_PROMPT_TEMPLATE,
                task, originalQuery, generatedSql, executionResult,
                attemptHistory.isEmpty() ? "无（首次尝试）" : attemptHistory,
                currentAttempt, DEFAULT_MAX_ATTEMPTS);

        try {
            String response = workerModel.generate(prompt);
            log.debug("反思引擎输出: {}", response);

            return parseReflection(response);
        } catch (Exception e) {
            log.error("反思引擎调用失败: {}", e.getMessage());
            return new ReflectionResult(
                    "反思引擎调用失败: " + e.getMessage(),
                    "无法给出修正建议",
                    false
            );
        }
    }

    /**
     * 带状态机上下文的反思分析（高级用法）
     *
     * 自动驱动状态转移:
     * 1. 如果当前不在 REFLECTING 态，尝试转移到 REFLECTING
     * 2. 调用 LLM 反思
     * 3. 根据结果: shouldRetry=true → ACTING, shouldRetry=false → FAILED
     *
     * @param ctx             状态机上下文
     * @param task            子任务
     * @param originalQuery   用户问题
     * @param action          本轮执行的操作
     * @param executionResult 执行结果
     * @return 反思结果
     */
    public ReflectionResult reflectWithContext(StateMachineContext ctx,
                                                String task, String originalQuery,
                                                String action, String executionResult) {
        // 确保处于 REFLECTING 态
        if (ctx.getState() != AgentState.REFLECTING) {
            AgentStateMachine.transitionTo(ctx, AgentState.REFLECTING);
        }

        // 记录本次尝试
        ctx.addAttemptRecord(action, executionResult);

        // 执行反思
        ReflectionResult result = reflect(task, originalQuery, action, executionResult,
                ctx.formatHistory());

        // 根据结果驱动后续状态转移
        if (result.shouldRetry() && ctx.hasRemainingAttempts()) {
            AgentStateMachine.transitionTo(ctx, AgentState.ACTING);
        } else if (!result.shouldRetry()) {
            AgentStateMachine.transitionTo(ctx, AgentState.FAILED);
        } else {
            // shouldRetry=true 但已无重试配额
            AgentStateMachine.transitionTo(ctx, AgentState.FAILED);
        }

        return result;
    }

    // ========================================================================
    // 解析逻辑
    // ========================================================================

    /**
     * 解析 LLM 反思输出，提取结构化信息
     */
    private ReflectionResult parseReflection(String response) {
        String failureReason = extractSection(response, "失败原因");
        String suggestion = extractSection(response, "修正建议");
        boolean shouldRetry = extractRetryDecision(response);

        return new ReflectionResult(failureReason, suggestion, shouldRetry);
    }

    /**
     * 从反思文本中提取指定章节内容
     */
    private String extractSection(String text, String sectionName) {
        String[] lines = text.split("\n");
        StringBuilder content = new StringBuilder();
        boolean capturing = false;

        for (String line : lines) {
            if (line.contains(sectionName)) {
                capturing = true;
                int colonIdx = Math.max(line.indexOf(':'), line.indexOf('：'));
                if (colonIdx >= 0 && colonIdx < line.length() - 1) {
                    content.append(line.substring(colonIdx + 1).strip());
                }
                continue;
            }
            if (capturing) {
                if (line.matches("^\\d+\\.\\s*\\*\\*.*\\*\\*.*") || line.matches("^##.*")) {
                    break;
                }
                if (!line.isBlank()) {
                    if (!content.isEmpty()) content.append(" ");
                    content.append(line.strip());
                }
            }
        }

        return content.isEmpty() ? "未提取到" + sectionName : content.toString();
    }

    /**
     * 从反思文本中提取是否重试的判断
     */
    private boolean extractRetryDecision(String text) {
        String upper = text.toUpperCase();
        int idx = upper.indexOf("是否重试");
        if (idx >= 0) {
            String after = upper.substring(idx);
            int yesIdx = after.indexOf("YES");
            int noIdx = after.indexOf("NO");
            if (yesIdx >= 0 && (noIdx < 0 || yesIdx < noIdx)) {
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * 从历史记录文本中推断当前尝试次数
     */
    private int countAttempts(String history) {
        if (history == null || history.isEmpty()) return 1;
        int count = 0;
        int idx = 0;
        while ((idx = history.indexOf("第 ", idx)) >= 0) {
            count++;
            idx += 2;
        }
        return count + 1; // 当前是下一次
    }

    private String buildKey(String sessionId, String agentName) {
        return sessionId + "::" + agentName;
    }

    // ========================================================================
    // 公开类型
    // ========================================================================

    /**
     * 反思结果
     */
    public record ReflectionResult(
            /** 失败原因分析 */
            String failureReason,
            /** 修正建议 */
            String suggestion,
            /** 是否建议重试 */
            boolean shouldRetry
    ) {
        /**
         * 格式化为可读文本（用于追加到下一轮 Prompt）
         */
        public String toPromptText() {
            return String.format("反思: %s\n建议: %s\n是否重试: %s",
                    failureReason, suggestion, shouldRetry ? "YES" : "NO");
        }
    }
}
