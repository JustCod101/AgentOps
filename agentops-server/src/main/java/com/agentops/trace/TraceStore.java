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
 * 记录 Agent 的每一步:
 * - THOUGHT: 思考推理过程
 * - ACTION: 调用 Tool
 * - OBSERVATION: 观察结果
 * - REFLECTION: 反思纠错
 * - DECISION: 路由决策
 */
@Service
public class TraceStore {

    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);

    private final AgentTraceRepository traceRepo;

    /** 每个 session 独立的步骤计数器 */
    private final ConcurrentHashMap<String, AtomicInteger> stepCounters = new ConcurrentHashMap<>();

    /** 实时监听器（用于 SSE 推送） */
    private final ConcurrentHashMap<String, List<TraceListener>> listeners = new ConcurrentHashMap<>();

    public TraceStore(AgentTraceRepository traceRepo) {
        this.traceRepo = traceRepo;
    }

    public void recordThought(String sessionId, String agentName, String thought) {
        save(sessionId, agentName, "THOUGHT", thought, null, null, null, null);
    }

    public void recordAction(String sessionId, String agentName, String toolName,
                             Map<String, Object> input, String output, boolean success) {
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

    public void recordObservation(String sessionId, String agentName, String observation) {
        save(sessionId, agentName, "OBSERVATION", observation, null, null, null, null);
    }

    public void recordReflection(String sessionId, String agentName, String reflection) {
        save(sessionId, agentName, "REFLECTION", reflection, null, null, null, null);
    }

    public void recordDecision(String sessionId, String agentName, String decision) {
        save(sessionId, agentName, "DECISION", decision, null, null, null, null);
    }

    /**
     * 获取完整排障轨迹（用于回放和报告）
     */
    public List<AgentTrace> getFullTrace(String sessionId) {
        return traceRepo.findBySessionIdOrderByStepIndex(sessionId);
    }

    /**
     * 获取排障轨迹的 Timeline 格式（用于前端可视化）
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
     * 注册实时监听器（用于 SSE 推送）
     */
    public void addListener(String sessionId, TraceListener listener) {
        listeners.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * 移除监听器
     */
    public void removeListeners(String sessionId) {
        listeners.remove(sessionId);
    }

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
            log.error("Trace 持久化失败 [session={}, agent={}]: {}",
                    trace.getSessionId(), trace.getAgentName(), e.getMessage());
        }
    }

    private void notifyListeners(AgentTrace trace) {
        List<TraceListener> sessionListeners = listeners.get(trace.getSessionId());
        if (sessionListeners != null) {
            for (TraceListener listener : sessionListeners) {
                try {
                    listener.onTrace(trace);
                } catch (Exception e) {
                    log.warn("Trace 监听器回调失败: {}", e.getMessage());
                }
            }
        }
    }

    private int nextStep(String sessionId) {
        return stepCounters.computeIfAbsent(sessionId, k -> new AtomicInteger(0))
                .incrementAndGet();
    }
}
