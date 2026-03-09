package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import com.agentops.agent.model.DiagnosisResult.DiagnosisReport;
import com.agentops.agent.model.TaskPlan;
import com.agentops.trace.TraceStore;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Report Agent — 报告生成专家
 *
 * 职责:
 * 1. 汇总所有 Worker Agent（TEXT2SQL / LOG_ANALYSIS / METRIC_QUERY）的执行结果
 * 2. 调用 LLM 进行多源证据关联分析，推断根因
 * 3. 输出结构化诊断报告（Markdown 格式）
 *
 * 报告结构:
 * - 故障概述: 一句话描述问题
 * - 根因分析: 关联多个 Agent 发现，推断因果链
 * - 影响范围: 受影响的服务、接口、用户群体
 * - 修复建议: 按优先级排序的可操作步骤
 * - 诊断置信度: 0.0 ~ 1.0
 */
@Service
public class ReportAgent {

    private static final Logger log = LoggerFactory.getLogger(ReportAgent.class);

    private static final String AGENT_NAME = "REPORT";

    /**
     * 报告生成 System Prompt
     *
     * 要求 LLM 扮演资深 SRE，综合多源数据输出结构化报告。
     * 用特殊标记（<ROOT_CAUSE>、<CONFIDENCE>）方便程序解析关键字段。
     */
    private static final String REPORT_SYSTEM_PROMPT = """
            你是一个资深 SRE（Site Reliability Engineer），专精于生产环境故障诊断。
            你将收到多个诊断 Agent 的分析结果，请综合所有证据，生成一份完整的排障报告。

            ## 输出格式要求

            请严格按照以下 Markdown 格式输出报告:

            ```
            # 诊断报告

            ## 1. 故障概述
            用一段话概括故障现象、发生时间、影响范围。

            ## 2. 根因分析
            <ROOT_CAUSE>一句话根因描述</ROOT_CAUSE>

            ### 证据链
            按时间顺序列出各 Agent 发现的关键证据，并标注因果关系:
            - [TEXT2SQL] 发现 ...
            - [LOG_ANALYSIS] 发现 ...
            - [METRIC_QUERY] 发现 ...

            ### 因果推断
            A → B → C 的故障传播路径描述。

            ## 3. 影响范围
            - **受影响服务**: ...
            - **受影响接口**: ...
            - **影响时长**: ...
            - **严重程度**: P0/P1/P2/P3

            ## 4. 修复建议
            按优先级排序:
            1. 🔴 **紧急**: ...
            2. 🟡 **重要**: ...
            3. 🟢 **改进**: ...

            ## 5. 诊断置信度
            <CONFIDENCE>0.85</CONFIDENCE>
            置信度说明: ...
            ```

            ## 分析规则
            1. **证据优先**: 所有结论必须有对应的 Agent 数据支撑，不得凭空推测
            2. **因果关联**: 尝试建立不同 Agent 发现之间的因果关系
            3. **置信度评估**: 根据证据充分程度打分:
               - 0.9+: 多个 Agent 证据一致指向同一根因
               - 0.7-0.9: 主要证据明确，部分细节待确认
               - 0.5-0.7: 有一定线索但证据不充分
               - <0.5: 数据不足，仅为初步推测
            4. **修复建议**: 必须是可操作的具体步骤，不要泛泛而谈
            5. 如果某个 Agent 执行失败（无数据），在报告中注明数据缺失，并降低置信度
            """;

    private final ChatLanguageModel routerModel;
    private final TraceStore traceStore;

    public ReportAgent(@Qualifier("routerModel") ChatLanguageModel routerModel,
                        TraceStore traceStore) {
        this.routerModel = routerModel;
        this.traceStore = traceStore;
    }

    /**
     * 生成诊断报告
     *
     * @param userQuery    用户原始问题
     * @param plan         任务计划（含意图分类和 reasoning）
     * @param agentResults 各 Worker Agent 的执行结果
     * @param sessionId    诊断会话 ID
     * @return 结构化诊断报告
     */
    public DiagnosisReport generateReport(String userQuery, TaskPlan plan,
                                          Map<String, AgentResult> agentResults,
                                          String sessionId) {
        log.info("ReportAgent 开始生成报告 [session={}]: {} 个 Agent 结果", sessionId, agentResults.size());

        // ======================== THOUGHT ========================
        String thought = String.format(
                "收到 %d 个 Agent 的执行结果，意图类型: %s\n"
                + "成功的 Agent: %s\n失败的 Agent: %s\n"
                + "计划: 汇总所有证据 → 调用 LLM 综合分析 → 输出结构化报告",
                agentResults.size(),
                plan != null ? plan.getIntent() : "UNKNOWN",
                getAgentsByStatus(agentResults, true),
                getAgentsByStatus(agentResults, false));
        traceStore.recordThought(sessionId, AGENT_NAME, thought);

        // ======================== ACT: 构建 Prompt ========================
        String userPrompt = buildUserPrompt(userQuery, plan, agentResults);
        traceStore.recordAction(sessionId, AGENT_NAME, "BUILD_REPORT_PROMPT",
                Map.of("agentCount", agentResults.size(),
                       "intent", plan != null ? plan.getIntent() : "UNKNOWN"),
                "Prompt 长度: " + userPrompt.length() + " 字符", true);

        // ======================== ACT: 调用 LLM 生成报告 ========================
        String reportMarkdown;
        try {
            reportMarkdown = routerModel.generate(REPORT_SYSTEM_PROMPT + "\n\n" + userPrompt);

            traceStore.recordAction(sessionId, AGENT_NAME, "LLM_GENERATE_REPORT",
                    Map.of("promptLength", userPrompt.length()),
                    "报告长度: " + reportMarkdown.length() + " 字符", true);
        } catch (Exception e) {
            log.error("LLM 报告生成失败 [session={}]: {}", sessionId, e.getMessage());
            traceStore.recordAction(sessionId, AGENT_NAME, "LLM_GENERATE_REPORT",
                    Map.of("error", e.getMessage()),
                    null, false);

            // 降级: 生成一份基于原始数据的简要报告
            return buildFallbackReport(userQuery, plan, agentResults, sessionId);
        }

        // ======================== OBSERVE: 解析报告 ========================
        String rootCause = extractTagContent(reportMarkdown, "ROOT_CAUSE");
        float confidence = parseConfidence(extractTagContent(reportMarkdown, "CONFIDENCE"));
        String fixSuggestion = extractFixSuggestions(reportMarkdown);

        traceStore.recordObservation(sessionId, AGENT_NAME,
                String.format("报告解析完成: 根因=%s, 置信度=%.2f", rootCause, confidence));

        // ======================== DECISION ========================
        traceStore.recordDecision(sessionId, AGENT_NAME,
                String.format("诊断报告生成完毕，置信度 %.0f%%，根因: %s",
                        confidence * 100, rootCause));

        log.info("ReportAgent 报告生成完成 [session={}]: confidence={}", sessionId, confidence);

        return new DiagnosisReport(rootCause, fixSuggestion, reportMarkdown, confidence);
    }

    /**
     * 构建 User Prompt — 汇总所有 Agent 结果
     */
    private String buildUserPrompt(String userQuery, TaskPlan plan,
                                    Map<String, AgentResult> agentResults) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("## 用户问题\n").append(userQuery).append("\n\n");

        // 路由分析
        if (plan != null) {
            prompt.append("## Router 分析\n");
            prompt.append("- 意图类型: ").append(plan.getIntent()).append("\n");
            prompt.append("- 路由置信度: ").append(plan.getConfidence()).append("\n");
            if (plan.getReasoning() != null) {
                prompt.append("- 路由推理: ").append(plan.getReasoning()).append("\n");
            }
            prompt.append("\n");
        }

        // 各 Agent 结果
        prompt.append("## Agent 执行结果\n\n");
        for (Map.Entry<String, AgentResult> entry : agentResults.entrySet()) {
            String agentName = entry.getKey();
            AgentResult result = entry.getValue();

            prompt.append("### ").append(agentName).append("\n");
            prompt.append("- 状态: ").append(result.isSuccess() ? "✅ 成功" : "❌ 失败").append("\n");

            if (result.isSuccess()) {
                if (result.getExplanation() != null) {
                    prompt.append("- 概要: ").append(result.getExplanation()).append("\n");
                }
                if (result.getData() != null) {
                    // 截取数据避免 Prompt 过长
                    String data = result.getData();
                    if (data.length() > 3000) {
                        data = data.substring(0, 3000) + "\n... (数据已截断，共 " + result.getData().length() + " 字符)";
                    }
                    prompt.append("\n#### 详细数据\n").append(data).append("\n");
                }
            } else {
                prompt.append("- 错误: ").append(result.getErrorMessage()).append("\n");
            }
            prompt.append("\n");
        }

        return prompt.toString();
    }

    /**
     * 降级报告 — LLM 调用失败时基于原始数据生成简要报告
     */
    private DiagnosisReport buildFallbackReport(String userQuery, TaskPlan plan,
                                                 Map<String, AgentResult> agentResults,
                                                 String sessionId) {
        log.warn("使用降级报告 [session={}]", sessionId);
        traceStore.recordDecision(sessionId, AGENT_NAME, "LLM 不可用，使用降级模式生成报告");

        StringBuilder markdown = new StringBuilder();
        markdown.append("# 诊断报告（降级模式）\n\n");
        markdown.append("> ⚠️ LLM 分析不可用，以下为原始数据汇总\n\n");

        markdown.append("## 用户问题\n").append(userQuery).append("\n\n");

        if (plan != null) {
            markdown.append("## 意图识别\n");
            markdown.append("- 类型: ").append(plan.getIntent()).append("\n\n");
        }

        // 汇总每个 Agent 的结果
        StringBuilder rootCauseParts = new StringBuilder();
        String fixSuggestion = "请人工分析以下 Agent 原始数据";

        for (Map.Entry<String, AgentResult> entry : agentResults.entrySet()) {
            AgentResult result = entry.getValue();
            markdown.append("## ").append(entry.getKey()).append("\n");

            if (result.isSuccess()) {
                markdown.append("**状态**: 成功\n\n");
                if (result.getExplanation() != null) {
                    markdown.append(result.getExplanation()).append("\n\n");
                    rootCauseParts.append(entry.getKey()).append(": ")
                                 .append(result.getExplanation()).append("; ");
                }
                if (result.getData() != null) {
                    markdown.append(result.getData()).append("\n\n");
                }
            } else {
                markdown.append("**状态**: 失败 — ").append(result.getErrorMessage()).append("\n\n");
            }
        }

        String rootCause = rootCauseParts.isEmpty()
                ? "数据不足，无法自动推断根因"
                : rootCauseParts.toString();

        // 降级报告置信度固定为 0.3
        float confidence = 0.3f;
        markdown.append("## 诊断置信度\n")
                .append("0.3（降级模式，仅供参考）\n");

        return new DiagnosisReport(rootCause, fixSuggestion, markdown.toString(), confidence);
    }

    /**
     * 提取标记内容: <TAG>content</TAG>
     */
    private String extractTagContent(String text, String tagName) {
        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";

        int start = text.indexOf(openTag);
        int end = text.indexOf(closeTag);

        if (start >= 0 && end > start) {
            return text.substring(start + openTag.length(), end).strip();
        }

        // 标记不存在，尝试从上下文推断
        if ("ROOT_CAUSE".equals(tagName)) {
            return extractFallbackRootCause(text);
        }
        return "未提取到";
    }

    /**
     * 备选根因提取 — 从"根因分析"章节提取第一句
     */
    private String extractFallbackRootCause(String text) {
        String[] markers = {"## 2. 根因分析", "## 根因分析", "根因:"};
        for (String marker : markers) {
            int idx = text.indexOf(marker);
            if (idx >= 0) {
                String after = text.substring(idx + marker.length()).strip();
                // 取第一个非空行
                for (String line : after.split("\n")) {
                    String trimmed = line.strip();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("<")) {
                        return trimmed;
                    }
                }
            }
        }
        return "根因待人工确认";
    }

    /**
     * 提取修复建议章节
     */
    private String extractFixSuggestions(String text) {
        String[] markers = {"## 4. 修复建议", "## 修复建议"};
        for (String marker : markers) {
            int start = text.indexOf(marker);
            if (start >= 0) {
                // 提取到下一个 ## 或文末
                String after = text.substring(start + marker.length());
                int nextSection = after.indexOf("\n## ");
                String section = nextSection >= 0 ? after.substring(0, nextSection) : after;
                return section.strip();
            }
        }
        return "请参考报告详情中的修复建议";
    }

    /**
     * 解析置信度数值
     */
    private float parseConfidence(String raw) {
        if (raw == null || raw.isEmpty() || "未提取到".equals(raw)) {
            return 0.5f; // 默认中等置信度
        }
        try {
            // 清理非数字字符（如 "0.85" 或 "85%"）
            String cleaned = raw.replaceAll("[^0-9.]", "");
            float value = Float.parseFloat(cleaned);
            // 如果是百分比形式（>1），转换为小数
            if (value > 1.0f) value = value / 100.0f;
            // 限定范围
            return Math.max(0.0f, Math.min(1.0f, value));
        } catch (NumberFormatException e) {
            return 0.5f;
        }
    }

    /**
     * 按成功/失败分组获取 Agent 名称
     */
    private String getAgentsByStatus(Map<String, AgentResult> results, boolean success) {
        String agents = results.entrySet().stream()
                .filter(e -> e.getValue().isSuccess() == success)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));
        return agents.isEmpty() ? "无" : agents;
    }
}
