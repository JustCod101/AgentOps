package com.agentops.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {

    private String agentName;
    private boolean success;
    private String data;
    private String explanation;
    private String errorMessage;

    public static AgentResult success(String agentName, String data, String explanation) {
        return AgentResult.builder()
                .agentName(agentName)
                .success(true)
                .data(data)
                .explanation(explanation)
                .build();
    }

    public static AgentResult failure(String agentName, String errorMessage) {
        return AgentResult.builder()
                .agentName(agentName)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
