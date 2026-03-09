package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import com.agentops.agent.model.DiagnosisResult.DiagnosisReport;
import com.agentops.agent.model.TaskPlan;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Report Agent — 报告生成专家
 *
 * 汇总各 Worker Agent 的结果，生成结构化排障报告
 */
@Service
public class ReportAgent {

    /**
     * 生成诊断报告
     *
     * @param userQuery    用户原始问题
     * @param plan         任务计划（含意图分类）
     * @param agentResults 各 Worker Agent 的执行结果
     * @param sessionId    诊断会话 ID
     * @return 结构化诊断报告
     */
    public DiagnosisReport generateReport(String userQuery, TaskPlan plan,
                                          Map<String, AgentResult> agentResults,
                                          String sessionId) {
        // TODO: 实现报告生成逻辑
        return new DiagnosisReport("待分析", "待生成", "# 报告生成中...", 0.0f);
    }
}
