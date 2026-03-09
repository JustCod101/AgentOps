package com.agentops.agent;

import org.springframework.stereotype.Service;

/**
 * Router Agent — 多智能体编排核心
 *
 * 职责:
 * 1. 意图识别: 判断问题类型
 * 2. 任务分解: 将复杂问题拆解为多个子任务
 * 3. Agent 派发: 按依赖关系调度 Worker Agent
 * 4. 结果汇总: 收集各 Agent 输出，触发 Report Agent
 */
@Service
public class RouterAgent {
    // TODO: 实现路由编排逻辑
}
