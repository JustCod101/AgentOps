package com.agentops.agent;

import com.agentops.agent.model.AgentResult;
import org.springframework.stereotype.Service;

/**
 * MetricQuery Agent — 系统指标查询专家
 */
@Service
public class MetricQueryAgent {

    public AgentResult execute(String task, String sessionId, String originalQuery) {
        // TODO: 实现指标查询逻辑
        return AgentResult.failure("METRIC_QUERY", "MetricQueryAgent 尚未实现");
    }
}
