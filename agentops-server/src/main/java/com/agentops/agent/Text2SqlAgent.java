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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Text2SQL Agent — 数据库查询专家
 *
 * 核心能力:
 * 1. 根据自然语言生成 SQL 查询
 * 2. AST 安全拦截（SqlSanitizer）
 * 3. 只读沙盒执行（SandboxSqlExecutor）
 * 4. Act-Observe-Reflect 自纠错循环（最多 3 次）
 *
 * 执行流程:
 * ┌─────────┐     ┌──────────┐     ┌───────────┐     ┌───────────┐
 * │ THOUGHT │────▶│   ACT    │────▶│  OBSERVE  │────▶│  REFLECT  │
 * │ 理解任务 │     │ LLM生成SQL│     │ 安全检查+执行│     │ 分析结果   │
 * └─────────┘     └──────────┘     └───────────┘     └─────┬─────┘
 *                       ▲                                   │
 *                       │          重试（带反思历史）          │
 *                       └───────────────────────────────────┘
 */
@Service
public class Text2SqlAgent {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlAgent.class);

    /** 最大重试次数（含首次，共 3 轮） */
    private static final int MAX_ATTEMPTS = 3;

    /** Agent 标识名 */
    private static final String AGENT_NAME = "TEXT2SQL";

    /**
     * System Prompt — 定义 Text2SQL Agent 的角色和规则
     *
     * 包含:
     * - 角色定位：数据库查询专家
     * - 可用表结构（4 张监控表的完整字段）
     * - 严格约束：只能 SELECT、必须 LIMIT、时间条件用 created_at
     * - 输出格式：只返回纯 SQL，不要解释
     */
    private static final String SYSTEM_PROMPT = """
            你是一个 PostgreSQL 数据库查询专家。你的任务是根据用户的自然语言描述生成 SQL 查询。

            ## 可用的表结构

            ### slow_query_log — 慢查询日志
            | 列名 | 类型 | 说明 |
            |------|------|------|
            | id | BIGSERIAL | 主键 |
            | query_text | TEXT | SQL 查询文本 |
            | execution_time_ms | INTEGER | 执行时间(毫秒) |
            | database_name | VARCHAR(100) | 数据库名 |
            | user_name | VARCHAR(100) | 执行用户 |
            | rows_examined | BIGINT | 扫描行数 |
            | rows_sent | INTEGER | 返回行数 |
            | created_at | TIMESTAMP | 记录时间 |

            ### db_connection_status — 数据库连接池状态
            | 列名 | 类型 | 说明 |
            |------|------|------|
            | id | BIGSERIAL | 主键 |
            | total_connections | INTEGER | 总连接数 |
            | active_connections | INTEGER | 活跃连接数 |
            | idle_connections | INTEGER | 空闲连接数 |
            | waiting_connections | INTEGER | 等待连接数 |
            | max_connections | INTEGER | 最大连接数 |
            | pool_name | VARCHAR(100) | 连接池名称 |
            | created_at | TIMESTAMP | 记录时间 |

            ### service_error_log — 微服务错误日志
            | 列名 | 类型 | 说明 |
            |------|------|------|
            | id | BIGSERIAL | 主键 |
            | service_name | VARCHAR(100) | 服务名 |
            | error_level | VARCHAR(20) | 错误级别(ERROR/WARN/FATAL) |
            | error_message | TEXT | 错误信息 |
            | stack_trace | TEXT | 堆栈跟踪 |
            | request_path | VARCHAR(500) | 请求路径 |
            | request_method | VARCHAR(10) | HTTP 方法 |
            | response_code | INTEGER | 响应码 |
            | trace_id | VARCHAR(64) | 链路追踪 ID |
            | created_at | TIMESTAMP | 记录时间 |

            ### system_metric — 系统指标
            | 列名 | 类型 | 说明 |
            |------|------|------|
            | id | BIGSERIAL | 主键 |
            | metric_name | VARCHAR(100) | 指标名(cpu_usage/memory_usage/qps/p99_latency/disk_io/db_connections) |
            | metric_value | DECIMAL(10,2) | 指标值 |
            | metric_unit | VARCHAR(20) | 单位 |
            | host | VARCHAR(100) | 主机名 |
            | service_name | VARCHAR(100) | 服务名 |
            | created_at | TIMESTAMP | 记录时间 |

            ## 严格规则
            1. **只能生成 SELECT 语句**，禁止 INSERT/UPDATE/DELETE/DROP 等
            2. **必须包含 LIMIT**，最大 100
            3. **时间过滤使用 created_at 列**，用 NOW() - INTERVAL 'Xm/h/d' 格式
            4. **只能查询以上 4 张表**，不得访问其他表
            5. **只输出纯 SQL**，不要包含解释、markdown 代码块标记或其他文字

            ## 输出格式
            直接输出一条 SQL 语句，不要任何其他内容。
            """;

    private final ChatLanguageModel workerModel;
    private final SqlSanitizer sqlSanitizer;
    private final SandboxSqlExecutor sqlExecutor;
    private final ReflectionEngine reflectionEngine;
    private final TraceStore traceStore;

    public Text2SqlAgent(@Qualifier("workerModel") ChatLanguageModel workerModel,
                          SqlSanitizer sqlSanitizer,
                          SandboxSqlExecutor sqlExecutor,
                          ReflectionEngine reflectionEngine,
                          TraceStore traceStore) {
        this.workerModel = workerModel;
        this.sqlSanitizer = sqlSanitizer;
        this.sqlExecutor = sqlExecutor;
        this.reflectionEngine = reflectionEngine;
        this.traceStore = traceStore;
    }

    /**
     * 执行 Text2SQL 任务（Act-Observe-Reflect 循环）
     *
     * @param task          子任务描述（如"查询最近10分钟的慢SQL记录"）
     * @param sessionId     诊断会话 ID
     * @param originalQuery 用户原始问题（提供上下文）
     * @return Agent 执行结果
     */
    public AgentResult execute(String task, String sessionId, String originalQuery) {
        log.info("Text2SqlAgent 开始执行 [session={}]: task={}", sessionId, task);

        // ======================== THOUGHT ========================
        // 记录初始思考：理解任务、确定查询方向
        String thought = String.format(
                "收到任务: %s\n用户原始问题: %s\n"
                + "计划: 根据任务描述生成 SQL → 安全检查 → 沙盒执行 → 根据结果决定是否反思重试",
                task, originalQuery);
        traceStore.recordThought(sessionId, AGENT_NAME, thought);

        // 历史尝试记录（用于反思时提供上下文）
        List<AttemptRecord> attemptHistory = new ArrayList<>();

        // ======================== ACT-OBSERVE-REFLECT LOOP ========================
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            log.info("Text2SqlAgent 第 {} 次尝试 [session={}]", attempt, sessionId);

            // ---------- ACT: 调用 LLM 生成 SQL ----------
            String generatedSql = generateSql(task, originalQuery, attemptHistory, sessionId);
            if (generatedSql == null) {
                traceStore.recordObservation(sessionId, AGENT_NAME, "LLM 未能生成有效 SQL");
                return AgentResult.failure(AGENT_NAME, "LLM 生成 SQL 失败");
            }

            // 记录 ACTION: 生成 SQL
            traceStore.recordAction(sessionId, AGENT_NAME, "LLM_GENERATE_SQL",
                    Map.of("task", task, "attempt", attempt),
                    generatedSql, true);

            // ---------- OBSERVE: 安全检查 ----------
            SqlSanitizeResult sanitizeResult = sqlSanitizer.sanitize(generatedSql);

            if (!sanitizeResult.isAllowed()) {
                // 安全拦截 — 记录观察结果
                String observation = String.format(
                        "SQL 安全检查被拦截（第 %d 次）: %s\n原始 SQL: %s",
                        attempt, sanitizeResult.getRejectReason(), generatedSql);
                traceStore.recordObservation(sessionId, AGENT_NAME, observation);

                // 记录到历史
                attemptHistory.add(new AttemptRecord(
                        attempt, generatedSql, "安全拦截: " + sanitizeResult.getRejectReason()));

                // ---------- REFLECT: 分析拦截原因 ----------
                if (attempt < MAX_ATTEMPTS) {
                    ReflectionResult reflection = reflectionEngine.reflect(
                            task, originalQuery, generatedSql,
                            "安全拦截: " + sanitizeResult.getRejectReason(),
                            formatAttemptHistory(attemptHistory));
                    traceStore.recordReflection(sessionId, AGENT_NAME, reflection.toPromptText());

                    if (!reflection.shouldRetry()) {
                        log.info("反思引擎建议不再重试 [session={}]", sessionId);
                        return AgentResult.failure(AGENT_NAME,
                                "SQL 安全检查未通过且不建议重试: " + sanitizeResult.getRejectReason());
                    }
                }
                continue; // 下一轮重试
            }

            // 安全检查通过，使用消毒后的 SQL
            String safeSql = sanitizeResult.getSanitizedSql();
            traceStore.recordAction(sessionId, AGENT_NAME, "SQL_SANITIZE",
                    Map.of("originalSql", generatedSql,
                           "tables", sanitizeResult.getTablesAccessed()),
                    safeSql, true);

            // ---------- OBSERVE: 沙盒执行 ----------
            SqlExecutionResult execResult = sqlExecutor.execute(safeSql, sessionId);

            // 记录执行动作
            traceStore.recordAction(sessionId, AGENT_NAME, "SQL_EXECUTE",
                    Map.of("sql", safeSql, "attempt", attempt),
                    execResult.toFormattedText(),
                    execResult.isSuccess());

            // 情况 1: 执行出错
            if (execResult.hasError()) {
                String observation = String.format(
                        "SQL 执行出错（第 %d 次）: %s\nSQL: %s",
                        attempt, execResult.getError(), safeSql);
                traceStore.recordObservation(sessionId, AGENT_NAME, observation);

                attemptHistory.add(new AttemptRecord(
                        attempt, safeSql, "执行出错: " + execResult.getError()));

                // REFLECT
                if (attempt < MAX_ATTEMPTS) {
                    ReflectionResult reflection = reflectionEngine.reflect(
                            task, originalQuery, safeSql,
                            "执行出错: " + execResult.getError(),
                            formatAttemptHistory(attemptHistory));
                    traceStore.recordReflection(sessionId, AGENT_NAME, reflection.toPromptText());

                    if (!reflection.shouldRetry()) {
                        return AgentResult.failure(AGENT_NAME,
                                "SQL 执行出错且不建议重试: " + execResult.getError());
                    }
                }
                continue;
            }

            // 情况 2: 结果为空
            if (execResult.isEmpty()) {
                String observation = String.format(
                        "SQL 执行成功但结果为空（第 %d 次），耗时 %dms\nSQL: %s",
                        attempt, execResult.getLatencyMs(), safeSql);
                traceStore.recordObservation(sessionId, AGENT_NAME, observation);

                attemptHistory.add(new AttemptRecord(
                        attempt, safeSql, "结果为空（0 行）"));

                // REFLECT: 空结果也需要反思 — 可能时间范围不对、条件太严格
                if (attempt < MAX_ATTEMPTS) {
                    ReflectionResult reflection = reflectionEngine.reflect(
                            task, originalQuery, safeSql,
                            "查询成功但结果为空，可能时间范围不对或条件过于严格",
                            formatAttemptHistory(attemptHistory));
                    traceStore.recordReflection(sessionId, AGENT_NAME, reflection.toPromptText());

                    if (!reflection.shouldRetry()) {
                        // 即使不重试，空结果也算成功（只是没数据）
                        return buildSuccessResult(safeSql, execResult,
                                "查询成功但无匹配数据，可能当前时间段内没有相关记录");
                    }
                }
                continue;
            }

            // 情况 3: 查询成功且有数据 — 直接返回
            String successObs = String.format(
                    "SQL 执行成功（第 %d 次）: %d 行结果，耗时 %dms",
                    attempt, execResult.getRowCount(), execResult.getLatencyMs());
            traceStore.recordObservation(sessionId, AGENT_NAME, successObs);
            traceStore.recordDecision(sessionId, AGENT_NAME,
                    String.format("查询成功，返回 %d 行数据", execResult.getRowCount()));

            log.info("Text2SqlAgent 执行成功 [session={}]: {} 行, 第 {} 次尝试",
                    sessionId, execResult.getRowCount(), attempt);

            return buildSuccessResult(safeSql, execResult, null);
        }

        // ======================== 所有重试用尽 ========================
        log.warn("Text2SqlAgent 所有重试用尽 [session={}]", sessionId);
        traceStore.recordDecision(sessionId, AGENT_NAME,
                "已尝试 " + MAX_ATTEMPTS + " 次，均未获得有效结果");

        // 返回最后一次的尝试信息
        AttemptRecord lastAttempt = attemptHistory.get(attemptHistory.size() - 1);
        return AgentResult.failure(AGENT_NAME,
                String.format("经过 %d 次尝试均未成功。最后一次: %s", MAX_ATTEMPTS, lastAttempt.result));
    }

    /**
     * 调用 LLM 生成 SQL
     *
     * 构建 Prompt 包含:
     * - System Prompt（表结构 + 规则）
     * - 用户任务描述
     * - 如果有历史尝试，附加反思历史供 LLM 参考修正
     */
    private String generateSql(String task, String originalQuery,
                                List<AttemptRecord> history, String sessionId) {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("用户问题: ").append(originalQuery).append("\n");
        userPrompt.append("具体任务: ").append(task).append("\n");

        // 如果有历史尝试，附加反思上下文
        if (!history.isEmpty()) {
            userPrompt.append("\n## 之前的尝试（请根据反思修正 SQL）\n");
            for (AttemptRecord record : history) {
                userPrompt.append(String.format(
                        "第 %d 次: SQL = `%s` → 结果: %s\n",
                        record.attempt, record.sql, record.result));
            }
            userPrompt.append("\n请根据以上反思记录，生成一条修正后的 SQL。\n");
        }

        try {
            // 使用 System Prompt + User Prompt 调用 LLM
            String response = workerModel.generate(SYSTEM_PROMPT + "\n\n" + userPrompt);

            // 清理 LLM 输出：去除 markdown 代码块标记和多余空白
            String sql = cleanLlmOutput(response);

            if (sql.isBlank()) {
                log.warn("LLM 返回空 SQL [session={}]", sessionId);
                return null;
            }

            log.debug("LLM 生成 SQL [session={}]: {}", sessionId, sql);
            return sql;

        } catch (Exception e) {
            log.error("LLM 调用失败 [session={}]: {}", sessionId, e.getMessage());
            traceStore.recordAction(sessionId, AGENT_NAME, "LLM_GENERATE_SQL",
                    Map.of("task", task, "error", e.getMessage()),
                    null, false);
            return null;
        }
    }

    /**
     * 清理 LLM 输出
     *
     * 处理常见的 LLM 输出格式问题:
     * - ```sql ... ``` 代码块包裹
     * - ``` ... ``` 无语言标记的代码块
     * - 前后多余的空行和空白
     * - 末尾分号保留（SqlSanitizer 会处理）
     */
    private String cleanLlmOutput(String raw) {
        if (raw == null) return "";

        String cleaned = raw.strip();

        // 去除 ```sql ... ``` 代码块
        if (cleaned.startsWith("```")) {
            // 找到第一个换行（跳过 ```sql 行）
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            // 去除结尾 ```
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.strip();
        }

        return cleaned;
    }

    /**
     * 构建成功结果
     *
     * @param sql        执行的 SQL
     * @param execResult 执行结果
     * @param note       附加说明（如空结果说明），可为 null
     */
    private AgentResult buildSuccessResult(String sql, SqlExecutionResult execResult, String note) {
        // data 字段存放格式化的查询结果（Markdown 表格）
        String data = execResult.toFormattedText();

        // explanation 字段存放执行概要
        String explanation;
        if (note != null) {
            explanation = String.format("执行 SQL: %s\n%s", sql, note);
        } else {
            explanation = String.format("执行 SQL: %s\n返回 %d 行，耗时 %dms",
                    sql, execResult.getRowCount(), execResult.getLatencyMs());
        }

        return AgentResult.success(AGENT_NAME, data, explanation);
    }

    /**
     * 格式化历史尝试记录（用于反思引擎的上下文）
     */
    private String formatAttemptHistory(List<AttemptRecord> history) {
        if (history.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (AttemptRecord record : history) {
            sb.append(String.format("- 第 %d 次: SQL=`%s` → %s\n",
                    record.attempt, record.sql, record.result));
        }
        return sb.toString();
    }

    /**
     * 单次尝试记录（内部使用）
     */
    private record AttemptRecord(
            int attempt,
            String sql,
            String result
    ) {}
}
