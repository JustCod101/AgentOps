package com.agentops.trace;

import com.agentops.domain.entity.AgentTrace;

@FunctionalInterface
public interface TraceListener {
    void onTrace(AgentTrace trace);
}
