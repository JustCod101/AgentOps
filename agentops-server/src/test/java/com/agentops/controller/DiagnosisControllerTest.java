package com.agentops.controller;

import com.agentops.agent.RouterAgent;
import com.agentops.agent.model.DiagnosisResult;
import com.agentops.agent.model.DiagnosisResult.DiagnosisReport;
import com.agentops.common.Result;
import com.agentops.domain.entity.AgentTrace;
import com.agentops.domain.entity.DiagnosisSession;
import com.agentops.domain.entity.SqlAuditLog;
import com.agentops.repository.SessionRepository;
import com.agentops.repository.SqlAuditRepository;
import com.agentops.trace.TraceStore;
import com.agentops.trace.TraceTimelineItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiagnosisController 测试")
class DiagnosisControllerTest {

    @Mock private RouterAgent routerAgent;
    @Mock private TraceStore traceStore;
    @Mock private SessionRepository sessionRepo;
    @Mock private SqlAuditRepository sqlAuditRepo;

    private DiagnosisController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 同步 executor 用于测试（直接在当前线程执行）
    private final Executor syncExecutor = Runnable::run;

    private static final String SESSION_ID = "test-session-001";

    @BeforeEach
    void setUp() {
        controller = new DiagnosisController(
                routerAgent, traceStore, sessionRepo, sqlAuditRepo, objectMapper, syncExecutor);
    }

    private DiagnosisSession createSession(String status) {
        return DiagnosisSession.builder()
                .sessionId(SESSION_ID)
                .initialQuery("数据库响应变慢")
                .intentType("DB_SLOW_QUERY")
                .status(status)
                .confidence(0.92f)
                .rootCause("索引缺失")
                .createdAt(Instant.now())
                .build();
    }

    // ================================================================
    // 1. SSE 流式诊断
    // ================================================================

    @Nested
    @DisplayName("POST /stream — SSE 流式诊断")
    class StreamDiagnosis {

        @Test
        @DisplayName("正常诊断 → 返回 SseEmitter")
        void normalStream() {
            DiagnosisSession session = createSession("COMPLETED");
            DiagnosisReport report = new DiagnosisReport("索引缺失", "加索引", "# 报告", 0.92f);
            DiagnosisResult result = new DiagnosisResult(session, report, List.of());

            when(routerAgent.diagnose(anyString(), anyString())).thenReturn(result);

            var request = new DiagnosisController.DiagnosisRequest("数据库变慢");
            SseEmitter emitter = controller.streamDiagnosis(request);

            assertThat(emitter).isNotNull();
            // 验证注册了监听器
            verify(traceStore).addListener(anyString(), any());
            // 验证 routerAgent.diagnose 被调用
            verify(routerAgent).diagnose(eq("数据库变慢"), anyString());
        }

        @Test
        @DisplayName("诊断异常 → SSE 不抛异常到调用方")
        void errorDoesNotThrow() {
            when(routerAgent.diagnose(anyString(), anyString()))
                    .thenThrow(new RuntimeException("LLM 不可用"));

            var request = new DiagnosisController.DiagnosisRequest("测试");
            // 不应抛异常（异常通过 SSE error 事件推送）
            SseEmitter emitter = controller.streamDiagnosis(request);
            assertThat(emitter).isNotNull();
        }
    }

    // ================================================================
    // 2. 查询诊断结果
    // ================================================================

    @Nested
    @DisplayName("GET /{sessionId} — 查询结果")
    class GetSession {

        @Test
        @DisplayName("存在的 session → 返回结果和统计")
        void existingSession() {
            DiagnosisSession session = createSession("COMPLETED");
            when(sessionRepo.findBySessionId(SESSION_ID)).thenReturn(Optional.of(session));
            when(traceStore.getStats(SESSION_ID)).thenReturn(
                    new TraceStore.TraceStats(10, 2, 5, 1, 1, 1, 4, 1));

            Result<DiagnosisController.DiagnosisSessionResponse> result =
                    controller.getSession(SESSION_ID);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().session().getSessionId()).isEqualTo(SESSION_ID);
            assertThat(result.getData().traceStats().totalSteps()).isEqualTo(10);
        }

        @Test
        @DisplayName("不存在的 session → 404")
        void nonExistentSession() {
            when(sessionRepo.findBySessionId("unknown")).thenReturn(Optional.empty());

            Result<DiagnosisController.DiagnosisSessionResponse> result =
                    controller.getSession("unknown");

            assertThat(result.getCode()).isEqualTo(404);
        }
    }

    // ================================================================
    // 3. 轨迹回放
    // ================================================================

    @Nested
    @DisplayName("GET /{sessionId}/trace — 轨迹回放")
    class GetTrace {

        @Test
        @DisplayName("返回完整 Trace 列表")
        void returnFullTrace() {
            when(sessionRepo.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(createSession("COMPLETED")));

            List<AgentTrace> traces = List.of(
                    AgentTrace.builder().sessionId(SESSION_ID).stepIndex(1)
                            .stepType("THOUGHT").agentName("ROUTER").content("thinking")
                            .createdAt(Instant.now()).build(),
                    AgentTrace.builder().sessionId(SESSION_ID).stepIndex(2)
                            .stepType("ACTION").agentName("TEXT2SQL").content("action")
                            .toolName("LLM").toolSuccess(true)
                            .createdAt(Instant.now()).build()
            );
            when(traceStore.getFullTrace(SESSION_ID)).thenReturn(traces);

            Result<List<AgentTrace>> result = controller.getTrace(SESSION_ID);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).hasSize(2);
        }

        @Test
        @DisplayName("session 不存在 → 404")
        void nonExistentTrace() {
            when(sessionRepo.findBySessionId("unknown")).thenReturn(Optional.empty());

            Result<List<AgentTrace>> result = controller.getTrace("unknown");
            assertThat(result.getCode()).isEqualTo(404);
        }
    }

    // ================================================================
    // 4. Timeline
    // ================================================================

    @Nested
    @DisplayName("GET /{sessionId}/timeline — 时间线")
    class GetTimeline {

        @Test
        @DisplayName("返回 Timeline 格式轨迹")
        void returnTimeline() {
            when(sessionRepo.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(createSession("COMPLETED")));

            List<TraceTimelineItem> items = List.of(
                    TraceTimelineItem.builder()
                            .stepIndex(1).agentName("ROUTER").stepType("THOUGHT")
                            .content("thinking").timestamp(Instant.now()).build()
            );
            when(traceStore.getTimeline(SESSION_ID)).thenReturn(items);

            Result<List<TraceTimelineItem>> result = controller.getTimeline(SESSION_ID);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).hasSize(1);
            assertThat(result.getData().get(0).getStepType()).isEqualTo("THOUGHT");
        }
    }

    // ================================================================
    // 5. SQL 审计
    // ================================================================

    @Nested
    @DisplayName("GET /{sessionId}/sql-audit — SQL 审计")
    class GetSqlAudit {

        @Test
        @DisplayName("返回该 session 的所有 SQL 审计记录")
        void returnAuditLogs() {
            when(sessionRepo.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(createSession("COMPLETED")));

            List<SqlAuditLog> audits = List.of(
                    SqlAuditLog.builder()
                            .sessionId(SESSION_ID)
                            .originalSql("SELECT * FROM slow_query_log LIMIT 10")
                            .sanitizedSql("SELECT * FROM slow_query_log LIMIT 10")
                            .isAllowed(true).sqlType("SELECT")
                            .executed(true).executionMs(45).resultRows(5)
                            .createdAt(Instant.now()).build()
            );
            when(sqlAuditRepo.findBySessionId(SESSION_ID)).thenReturn(audits);

            Result<List<SqlAuditLog>> result = controller.getSqlAudit(SESSION_ID);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).hasSize(1);
            assertThat(result.getData().get(0).getOriginalSql()).contains("slow_query_log");
        }

        @Test
        @DisplayName("session 不存在 → 404")
        void nonExistentAudit() {
            when(sessionRepo.findBySessionId("unknown")).thenReturn(Optional.empty());

            Result<List<SqlAuditLog>> result = controller.getSqlAudit("unknown");
            assertThat(result.getCode()).isEqualTo(404);
        }
    }

    // ================================================================
    // 6. 历史列表
    // ================================================================

    @Nested
    @DisplayName("GET /history — 历史列表")
    class GetHistory {

        @Test
        @DisplayName("分页返回历史记录")
        void paginatedHistory() {
            List<DiagnosisSession> sessions = List.of(
                    createSession("COMPLETED"),
                    createSession("FAILED")
            );
            Page<DiagnosisSession> page = new PageImpl<>(sessions, PageRequest.of(0, 20), 2);
            when(sessionRepo.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(page);

            Result<Page<DiagnosisSession>> result = controller.getHistory(0, 20);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().getContent()).hasSize(2);
            assertThat(result.getData().getTotalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("size 超过 100 自动限制为 100")
        void maxSizeCapped() {
            Page<DiagnosisSession> emptyPage = new PageImpl<>(List.of());
            when(sessionRepo.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(emptyPage);

            controller.getHistory(0, 500);

            verify(sessionRepo).findAllByOrderByCreatedAtDesc(PageRequest.of(0, 100));
        }
    }
}
