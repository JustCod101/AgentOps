package com.agentops.trace;

import com.agentops.domain.entity.AgentTrace;
import com.agentops.repository.AgentTraceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TraceStore — Agent 全链路追踪持久化
 *
 * 核心能力:
 * 1. 5 种 Trace 类型记录: THOUGHT / ACTION / OBSERVATION / REFLECTION / DECISION
 * 2. 实时监听器: 注册 TraceListener，每条 Trace 写入时回调（用于 SSE 推送）
 * 3. 轨迹查询: getFullTrace() 完整轨迹，getTimeline() 前端可视化格式
 * 4. 按 session 隔离: 每个诊断会话独立的步骤计数器和监听器列表
 *
 * 线程安全保证:
 * - stepCounters: ConcurrentHashMap + AtomicInteger，多 Agent 并行写入互不干扰
 * - listeners: ConcurrentHashMap + CopyOnWriteArrayList，支持迭代中回调
 * - persist: DB 写入失败不阻塞主流程，仅 log.error
 */
@Service
public class TraceStore {

    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);

    private final AgentTraceRepository traceRepo;

    /** 每个 session 独立的步骤计数器，保证步骤编号全局递增 */
    private final ConcurrentHashMap<String, AtomicInteger> stepCounters = new ConcurrentHashMap<>();

    /** 实时监听器注册表（用于 SSE 推送），key = sessionId */
    private final ConcurrentHashMap<String, List<TraceListener>> listeners = new ConcurrentHashMap<>();

    public TraceStore(AgentTraceRepository traceRepo) {
        this.traceRepo = traceRepo;
    }

    // ========================================================================
    // 5 种 Trace 记录方法
    // ========================================================================

    /**
     * 记录 THOUGHT — Agent 的思考推理过程
     *
     * 用途: 记录 Agent 对任务的理解、查询方向的思考
     * 示例: "收到任务: 查询最近10分钟的慢SQL，计划: 生成SQL → 安全检查 → 沙盒执行"
     */
    public void recordThought(String sessionId, String agentName, String thought) {
        save(sessionId, agentName, "THOUGHT", thought, null, null, null, null);
    }

    /**
     * 记录 ACTION — Agent 调用工具/LLM 的操作
     *
     * 用途: 记录具体的工具调用，含输入参数和输出结果
     * 示例: toolName="LLM_GENERATE_SQL", input={task, attempt}, output="SELECT ..."
     *
     * @param toolName 工具名称（LLM_GENERATE_SQL / SQL_SANITIZE / SQL_EXECUTE / ...）
     * @param input    工具输入参数（JSONB 存储）
     * @param output   工具输出结果
     * @param success  是否成功
     */
    public void recordAction(String sessionId, String agentName, String toolName,
                             Map<String, Object> input, String output, boolean success) {
        long startNanos = System.nanoTime();

        AgentTrace trace = AgentTrace.builder()
                .sessionId(sessionId)
                .agentName(agentName)
                .stepIndex(nextStep(sessionId))
                .stepType("ACTION")
                .content("调用工具: " + toolName)
                .toolName(toolName)
                .toolInput(input)
                .toolOutput(output)
                .toolSuccess(success)
                .createdAt(Instant.now())
                .build();
        persist(trace);
    }

    /**
     * 记录 OBSERVATION — Agent 对执行结果的观察
     *
     * 用途: 记录查询结果的概要判断（成功/失败/为空）
     * 示例: "SQL 执行成功（第 1 次）: 5 行结果，耗时 45ms"
     */
    public void recordObservation(String sessionId, String agentName, String observation) {
        save(sessionId, agentName, "OBSERVATION", observation, null, null, null, null);
    }

    /**
     * 记录 REFLECTION — Agent 的反思纠错
     *
     * 用途: 记录反思引擎的分析结论（失败原因 + 修正建议 + 是否重试）
     * 示例: "反思: 列名拼写错误\n建议: 将 execution_time 改为 execution_time_ms\n是否重试: YES"
     */
    public void recordReflection(String sessionId, String agentName, String reflection) {
        save(sessionId, agentName, "REFLECTION", reflection, null, null, null, null);
    }

    /**
     * 记录 DECISION — Router 或 Agent 的路由/决策
     *
     * 用途: 记录关键决策点（意图识别、任务分派、最终结论）
     * 示例: "诊断完成, 置信度: 0.92, 总耗时: 3500ms"
     */
    public void recordDecision(String sessionId, String agentName, String decision) {
        save(sessionId, agentName, "DECISION", decision, null, null, null, null);
    }

    // ========================================================================
    // 轨迹查询
    // ========================================================================

    /**
     * 获取完整排障轨迹（用于回放和报告生成）
     *
     * 按 stepIndex 升序排列，包含所有 Agent 的全部步骤
     */
    public List<AgentTrace> getFullTrace(String sessionId) {
        return traceRepo.findBySessionIdOrderByStepIndex(sessionId);
    }

    /**
     * 获取指定 Agent 的轨迹
     */
    public List<AgentTrace> getAgentTrace(String sessionId, String agentName) {
        return traceRepo.findBySessionIdAndAgentNameOrderByStepIndex(sessionId, agentName);
    }

    /**
     * 获取排障轨迹的 Timeline 格式（用于前端可视化）
     *
     * 将 AgentTrace 实体转换为轻量级 DTO，
     * 去除 toolInput/toolOutput 等大字段，仅保留展示所需信息
     */
    public List<TraceTimelineItem> getTimeline(String sessionId) {
        return getFullTrace(sessionId).stream().map(t -> TraceTimelineItem.builder()
                .stepIndex(t.getStepIndex())
                .agentName(t.getAgentName())
                .stepType(t.getStepType())
                .content(t.getContent())
                .toolName(t.getToolName())
                .success(t.getToolSuccess())
                .latencyMs(t.getLatencyMs())
                .timestamp(t.getCreatedAt())
                .build()
        ).toList();
    }

    /**
     * 获取 Trace 统计信息
     */
    public TraceStats getStats(String sessionId) {
        List<AgentTrace> traces = getFullTrace(sessionId);
        int thoughts = 0, actions = 0, observations = 0, reflections = 0, decisions = 0;
        int successActions = 0, failedActions = 0;

        for (AgentTrace t : traces) {
            switch (t.getStepType()) {
                case "THOUGHT"     -> thoughts++;
                case "ACTION"      -> {
                    actions++;
                    if (Boolean.TRUE.equals(t.getToolSuccess())) successActions++;
                    else if (Boolean.FALSE.equals(t.getToolSuccess())) failedActions++;
                }
                case "OBSERVATION" -> observations++;
                case "REFLECTION"  -> reflections++;
                case "DECISION"    -> decisions++;
            }
        }

        return new TraceStats(traces.size(), thoughts, actions, observations,
                reflections, decisions, successActions, failedActions);
    }

    // ========================================================================
    // 实时监听器（SSE 推送）
    // ========================================================================

    /**
     * 注册实时监听器
     *
     * 注册后，该 session 的每条新 Trace 都会回调 listener.onTrace()。
     * 通常由 DiagnosisController 的 SSE 端点在建立连接时注册。
     */
    public void addListener(String sessionId, TraceListener listener) {
        listeners.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(listener);
        log.debug("注册 Trace 监听器 [session={}], 当前监听器数: {}",
                sessionId, listeners.get(sessionId).size());
    }

    /**
     * 移除指定监听器
     */
    public void removeListener(String sessionId, TraceListener listener) {
        List<TraceListener> sessionListeners = listeners.get(sessionId);
        if (sessionListeners != null) {
            sessionListeners.remove(listener);
            if (sessionListeners.isEmpty()) {
                listeners.remove(sessionId);
            }
        }
    }

    /**
     * 移除 session 的所有监听器（诊断完成或连接断开时调用）
     */
    public void removeListeners(String sessionId) {
        listeners.remove(sessionId);
        log.debug("移除所有 Trace 监听器 [session={}]", sessionId);
    }

    /**
     * 清理 session 相关资源（步骤计数器 + 监听器）
     */
    public void cleanupSession(String sessionId) {
        stepCounters.remove(sessionId);
        listeners.remove(sessionId);
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    private void save(String sessionId, String agentName, String type, String content,
                      String toolName, Map<String, Object> input, String output, Boolean success) {
        AgentTrace trace = AgentTrace.builder()
                .sessionId(sessionId)
                .agentName(agentName)
                .stepIndex(nextStep(sessionId))
                .stepType(type)
                .content(content)
                .toolName(toolName)
                .toolInput(input)
                .toolOutput(output)
                .toolSuccess(success)
                .createdAt(Instant.now())
                .build();
        persist(trace);
    }

    private void persist(AgentTrace trace) {
        try {
            traceRepo.save(trace);
            notifyListeners(trace);
        } catch (Exception e) {
            // DB 写入失败不阻塞 Agent 主流程
            log.error("Trace 持久化失败 [session={}, agent={}, step={}]: {}",
                    trace.getSessionId(), trace.getAgentName(),
                    trace.getStepIndex(), e.getMessage());
        }
    }

    /**
     * 通知所有注册的监听器（用于 SSE 实时推送）
     *
     * 单个监听器异常不影响其他监听器和主流程
     */
    private void notifyListeners(AgentTrace trace) {
        List<TraceListener> sessionListeners = listeners.get(trace.getSessionId());
        if (sessionListeners != null) {
            for (TraceListener listener : sessionListeners) {
                try {
                    listener.onTrace(trace);
                } catch (Exception e) {
                    log.warn("Trace 监听器回调失败 [session={}, listener={}]: {}",
                            trace.getSessionId(), listener.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }

    /**
     * 获取下一个步骤编号（线程安全，per-session 递增）
     */
    private int nextStep(String sessionId) {
        return stepCounters.computeIfAbsent(sessionId, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    // ========================================================================
    // 统计 DTO
    // ========================================================================

    /**
     * Trace 统计信息
     */
    public record TraceStats(
            int totalSteps,
            int thoughts,
            int actions,
            int observations,
            int reflections,
            int decisions,
            int successActions,
            int failedActions
    ) {}
}
