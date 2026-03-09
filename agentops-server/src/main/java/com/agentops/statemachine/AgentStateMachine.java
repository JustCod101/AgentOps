package com.agentops.statemachine;

/**
 * Agent 状态机
 */
public class AgentStateMachine {

    public enum AgentState {
        INIT, ACTING, OBSERVING, REFLECTING, DONE, FAILED
    }

    // TODO: 实现状态流转逻辑
}
