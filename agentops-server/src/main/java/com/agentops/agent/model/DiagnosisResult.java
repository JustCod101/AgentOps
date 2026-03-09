package com.agentops.agent.model;

import com.agentops.domain.entity.AgentTrace;
import com.agentops.domain.entity.DiagnosisSession;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisResult {

    private DiagnosisSession session;
    private DiagnosisReport report;
    private List<AgentTrace> traces;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiagnosisReport {
        private String rootCause;
        private String fixSuggestion;
        private String markdown;
        private float confidence;
    }
}
