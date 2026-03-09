package com.agentops.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskPlan {

    private String intent;
    private double confidence;
    private List<TaskItem> tasks;
    private String reasoning;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskItem {
        private String agent;
        private String task;
        private int priority;
    }
}
