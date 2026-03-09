package com.agentops.controller;

import com.agentops.agent.RouterAgent;
import com.agentops.agent.model.DiagnosisResult;
import com.agentops.common.Result;
import com.agentops.domain.entity.AgentTrace;
import com.agentops.domain.entity.DiagnosisSession;
import com.agentops.domain.entity.SqlAuditLog;
import com.agentops.repository.SessionRepository;
import com.agentops.repository.SqlAuditRepository;
import com.agentops.trace.TraceStore;
import com.agentops.trace.TraceTimelineItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 诊断 API — 对外暴露排障能力
 *
 * API 列表:
 * - POST   /api/v1/diagnosis/stream      SSE 流式诊断（实时推送每一步 Trace）
 * - GET    /api/v1/diagnosis/{sessionId}  查询诊断结果
 * - GET    /api/v1/diagnosis/{sessionId}/trace     轨迹回放（完整 Trace）
 * - GET    /api/v1/diagnosis/{sessionId}/timeline  轨迹时间线（前端可视化格式）
 * - GET    /api/v1/diagnosis/{sessionId}/sql-audit  SQL 审计记录
 * - GET    /api/v1/diagnosis/history       历史诊断列表（分页）
 */
@RestController
@RequestMapping("/api/v1/diagnosis")
public class DiagnosisController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisController.class);

    /** SSE 超时: 5 分钟（诊断可能耗时较长） */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final RouterAgent routerAgent;
    private final TraceStore traceStore;
    private final SessionRepository sessionRepo;
    private final SqlAuditRepository sqlAuditRepo;
    private final ObjectMapper objectMapper;
    private final Executor agentExecutor;

    public DiagnosisController(RouterAgent routerAgent,
                                TraceStore traceStore,
                                SessionRepository sessionRepo,
                                SqlAuditRepository sqlAuditRepo,
                                ObjectMapper objectMapper,
                                @org.springframework.beans.factory.annotation.Qualifier("agentExecutor")
                                Executor agentExecutor) {
        this.routerAgent = routerAgent;
        this.traceStore = traceStore;
        this.sessionRepo = sessionRepo;
        this.sqlAuditRepo = sqlAuditRepo;
        this.objectMapper = objectMapper;
        this.agentExecutor = agentExecutor;
    }

    // ========================================================================
    // 1. SSE 流式诊断
    // ========================================================================

    /**
     * POST /stream — 发起诊断并通过 SSE 实时推送每一步
     *
     * 请求体: { "query": "数据库响应变慢" }
     *
     * SSE 事件流:
     * - event: trace    → 每条 Trace（THOUGHT/ACTION/OBSERVATION/REFLECTION/DECISION）
     * - event: result   → 最终诊断结果
     * - event: error    → 诊断异常
     * - event: done     → 诊断完成信号
     *
     * 客户端示例:
     * const es = new EventSource('/api/v1/diagnosis/stream');
     * es.addEventListener('trace', e => console.log(JSON.parse(e.data)));
     * es.addEventListener('result', e => console.log(JSON.parse(e.data)));
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDiagnosis(@RequestBody DiagnosisRequest request) {
        String sessionId = UUID.randomUUID().toString();
        String query = request.query();

        log.info("收到诊断请求 [session={}]: {}", sessionId, query);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 注册 Trace 监听器 → 每条 Trace 实时推送到客户端
        traceStore.addListener(sessionId, trace -> {
            try {
                SseEmitter.SseEventBuilder event = SseEmitter.event()
                        .name("trace")
                        .data(objectMapper.writeValueAsString(buildTraceEvent(trace)));
                emitter.send(event);
            } catch (IOException e) {
                log.debug("SSE 发送失败 [session={}]: {}", sessionId, e.getMessage());
            }
        });

        // SSE 连接关闭时清理资源
        Runnable cleanup = () -> {
            traceStore.removeListeners(sessionId);
            log.debug("SSE 连接关闭 [session={}]", sessionId);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        // 发送 session_id 初始事件
        try {
            emitter.send(SseEmitter.event()
                    .name("session")
                    .data(objectMapper.writeValueAsString(Map.of("sessionId", sessionId))));
        } catch (IOException e) {
            log.error("SSE 发送 session 事件失败: {}", e.getMessage());
        }

        // 异步执行诊断（不阻塞 HTTP 线程）
        agentExecutor.execute(() -> {
            try {
                DiagnosisResult result = routerAgent.diagnose(query, sessionId);

                // 发送最终结果
                emitter.send(SseEmitter.event()
                        .name("result")
                        .data(objectMapper.writeValueAsString(buildResultEvent(result))));

                // 发送完成信号
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId,
                                "status", result.getSession().getStatus()))));

                emitter.complete();

            } catch (Exception e) {
                log.error("诊断执行异常 [session={}]: {}", sessionId, e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", sessionId,
                                    "error", e.getMessage() != null ? e.getMessage() : "未知错误"))));
                } catch (IOException ex) {
                    log.debug("SSE 发送错误事件失败: {}", ex.getMessage());
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    // ========================================================================
    // 2. 查询诊断结果
    // ========================================================================

    /**
     * GET /{sessionId} — 查询指定诊断会话的结果
     */
    @GetMapping("/{sessionId}")
    public Result<DiagnosisSessionResponse> getSession(@PathVariable String sessionId) {
        return sessionRepo.findBySessionId(sessionId)
                .map(session -> {
                    TraceStore.TraceStats stats = traceStore.getStats(sessionId);
                    return Result.ok(new DiagnosisSessionResponse(session, stats));
                })
                .orElse(Result.fail(404, "诊断会话不存在: " + sessionId));
    }

    // ========================================================================
    // 3. 轨迹回放
    // ========================================================================

    /**
     * GET /{sessionId}/trace — 获取完整 Trace 轨迹（含 toolInput/toolOutput）
     */
    @GetMapping("/{sessionId}/trace")
    public Result<List<AgentTrace>> getTrace(@PathVariable String sessionId) {
        if (sessionRepo.findBySessionId(sessionId).isEmpty()) {
            return Result.fail(404, "诊断会话不存在: " + sessionId);
        }
        return Result.ok(traceStore.getFullTrace(sessionId));
    }

    /**
     * GET /{sessionId}/timeline — 获取 Timeline 格式轨迹（前端可视化用）
     */
    @GetMapping("/{sessionId}/timeline")
    public Result<List<TraceTimelineItem>> getTimeline(@PathVariable String sessionId) {
        if (sessionRepo.findBySessionId(sessionId).isEmpty()) {
            return Result.fail(404, "诊断会话不存在: " + sessionId);
        }
        return Result.ok(traceStore.getTimeline(sessionId));
    }

    // ========================================================================
    // 4. SQL 审计
    // ========================================================================

    /**
     * GET /{sessionId}/sql-audit — 获取该诊断会话中所有 SQL 执行记录
     */
    @GetMapping("/{sessionId}/sql-audit")
    public Result<List<SqlAuditLog>> getSqlAudit(@PathVariable String sessionId) {
        if (sessionRepo.findBySessionId(sessionId).isEmpty()) {
            return Result.fail(404, "诊断会话不存在: " + sessionId);
        }
        return Result.ok(sqlAuditRepo.findBySessionId(sessionId));
    }

    // ========================================================================
    // 5. 历史列表
    // ========================================================================

    /**
     * GET /history — 分页查询历史诊断记录
     *
     * @param page 页码（从 0 开始）
     * @param size 每页大小（默认 20，最大 100）
     */
    @GetMapping("/history")
    public Result<Page<DiagnosisSession>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        return Result.ok(sessionRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size)));
    }

    // ========================================================================
    // 请求/响应 DTO
    // ========================================================================

    /**
     * 诊断请求
     */
    public record DiagnosisRequest(String query) {}

    /**
     * 诊断会话响应（含统计信息）
     */
    public record DiagnosisSessionResponse(
            DiagnosisSession session,
            TraceStore.TraceStats traceStats
    ) {}

    // ========================================================================
    // SSE 事件构建
    // ========================================================================

    /**
     * 构建 Trace SSE 事件数据
     *
     * 只推送前端需要的字段，不推送 toolInput/toolOutput 等大对象
     */
    private Map<String, Object> buildTraceEvent(AgentTrace trace) {
        var event = new java.util.LinkedHashMap<String, Object>();
        event.put("stepIndex", trace.getStepIndex());
        event.put("agentName", trace.getAgentName());
        event.put("stepType", trace.getStepType());
        event.put("content", trace.getContent());
        if (trace.getToolName() != null) {
            event.put("toolName", trace.getToolName());
        }
        if (trace.getToolSuccess() != null) {
            event.put("success", trace.getToolSuccess());
        }
        event.put("timestamp", trace.getCreatedAt().toString());
        return event;
    }

    /**
     * 构建最终结果 SSE 事件数据
     */
    private Map<String, Object> buildResultEvent(DiagnosisResult result) {
        var event = new java.util.LinkedHashMap<String, Object>();
        event.put("sessionId", result.getSession().getSessionId());
        event.put("status", result.getSession().getStatus());
        event.put("intentType", result.getSession().getIntentType());
        event.put("totalLatencyMs", result.getSession().getTotalLatencyMs());

        if (result.getReport() != null) {
            event.put("rootCause", result.getReport().getRootCause());
            event.put("confidence", result.getReport().getConfidence());
            event.put("fixSuggestion", result.getReport().getFixSuggestion());
            event.put("markdown", result.getReport().getMarkdown());
        }
        return event;
    }
}
