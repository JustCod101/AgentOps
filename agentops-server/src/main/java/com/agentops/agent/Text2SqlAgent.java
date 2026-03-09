package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import org.springframework.stereotype.Service;

/**
 * Text2SQL Agent — 数据库查询专家
 *
 * 核心能力:
 * 1. 根据自然语言生成 SQL 查询
 * 2. AST 安全拦截
 * 3. 只读沙盒执行
 * 4. Act-Observe-Reflect 自纠错循环
 */
@Service
public class Text2SqlAgent {

    /**
     * 执行 Text2SQL 任务
     *
     * @param task          子任务描述（如"查询最近10分钟的慢SQL记录"）
     * @param sessionId     诊断会话 ID
     * @param originalQuery 用户原始问题（提供上下文）
     * @return Agent 执行结果
     */
    public AgentResult execute(String task, String sessionId, String originalQuery) {
        // TODO: 实现 Text2SQL 完整逻辑
        return AgentResult.failure("TEXT2SQL", "Text2SqlAgent 尚未实现");
    }
}
