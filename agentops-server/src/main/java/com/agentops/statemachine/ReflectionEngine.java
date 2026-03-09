package com.agentops.statemachine;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 反思引擎 — Act-Observe-Reflect 自纠错机制的核心
 *
 * 当 Agent 执行结果不理想（空结果、报错、安全拦截）时，
 * 调用 LLM 分析失败原因并给出修正建议，指导下一轮重试。
 *
 * 输出结构化的反思结论：
 * - 失败原因分析
 * - 具体修正建议（如调整时间范围、换用其他字段、修正 SQL 语法）
 * - 是否值得重试的判断
 */
@Service
public class ReflectionEngine {

    private static final Logger log = LoggerFactory.getLogger(ReflectionEngine.class);

    private final ChatLanguageModel workerModel;

    /**
     * 反思 Prompt 模板
     * 提供上下文：原始任务、生成的 SQL、执行结果/错误、历史尝试
     * 要求 LLM 输出：原因分析 + 修正建议 + 是否重试
     */
    private static final String REFLECTION_PROMPT_TEMPLATE = """
            你是一个数据库查询调试专家。请分析以下 SQL 查询失败的原因，并给出修正建议。

            ## 原始任务
            %s

            ## 用户原始问题
            %s

            ## 生成的 SQL
            ```sql
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

            ## 请输出以下内容（纯文本，不要 JSON）：
            1. **失败原因**: 一句话总结为什么查询失败或结果为空
            2. **修正建议**: 具体的 SQL 修改方向（如：扩大时间范围、使用正确的列名、调整 WHERE 条件等）
            3. **是否重试**: YES 或 NO（如果错误是根本性的，比如表不存在，则 NO）
            """;

    public ReflectionEngine(@Qualifier("workerModel") ChatLanguageModel workerModel) {
        this.workerModel = workerModel;
    }

    /**
     * 执行反思分析
     *
     * @param task           子任务描述
     * @param originalQuery  用户原始问题
     * @param generatedSql   本轮生成的 SQL
     * @param executionResult 执行结果（成功但为空）或错误信息
     * @param attemptHistory 历史尝试摘要（前几轮的 SQL + 结果）
     * @return 结构化反思结果
     */
    public ReflectionResult reflect(String task, String originalQuery,
                                     String generatedSql, String executionResult,
                                     String attemptHistory) {
        log.debug("开始反思分析: task={}, sql={}", task, generatedSql);

        String prompt = String.format(REFLECTION_PROMPT_TEMPLATE,
                task, originalQuery, generatedSql, executionResult,
                attemptHistory.isEmpty() ? "无（首次尝试）" : attemptHistory);

        try {
            String response = workerModel.generate(prompt);
            log.debug("反思引擎输出: {}", response);

            return parseReflection(response);
        } catch (Exception e) {
            log.error("反思引擎调用失败: {}", e.getMessage());
            // 反思失败不阻塞主流程，返回默认不重试
            return new ReflectionResult(
                    "反思引擎调用失败: " + e.getMessage(),
                    "无法给出修正建议",
                    false
            );
        }
    }

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
        // 匹配 "**失败原因**:" 或 "失败原因:" 后面的内容，直到下一个 "**" 或文末
        String[] lines = text.split("\n");
        StringBuilder content = new StringBuilder();
        boolean capturing = false;

        for (String line : lines) {
            if (line.contains(sectionName)) {
                capturing = true;
                // 提取冒号/：后面的内容
                int colonIdx = Math.max(line.indexOf(':'), line.indexOf('：'));
                if (colonIdx >= 0 && colonIdx < line.length() - 1) {
                    content.append(line.substring(colonIdx + 1).strip());
                }
                continue;
            }
            if (capturing) {
                // 遇到下一个章节标题停止
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
        // 查找 "是否重试" 后面的 YES/NO
        String upper = text.toUpperCase();
        int idx = upper.indexOf("是否重试");
        if (idx >= 0) {
            String after = upper.substring(idx);
            // YES 出现在 NO 之前则重试
            int yesIdx = after.indexOf("YES");
            int noIdx = after.indexOf("NO");
            if (yesIdx >= 0 && (noIdx < 0 || yesIdx < noIdx)) {
                return true;
            }
            return false;
        }
        // 默认不重试（保守策略）
        return false;
    }

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
