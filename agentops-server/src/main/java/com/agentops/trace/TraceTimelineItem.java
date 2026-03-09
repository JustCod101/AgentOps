package com.agentops.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceTimelineItem {

    private int stepIndex;
    private String agentName;
    private String stepType;
    private String content;
    private String toolName;
    private Boolean success;
    private Integer latencyMs;
    private Instant timestamp;
}
