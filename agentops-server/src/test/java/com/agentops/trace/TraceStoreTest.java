package com.agentops.trace;

import com.agentops.domain.entity.AgentTrace;
import com.agentops.repository.AgentTraceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TraceStore 全链路追踪测试")
class TraceStoreTest {

    @Mock private AgentTraceRepository traceRepo;

    private TraceStore traceStore;

    private static final String SESSION = "trace-test-001";
    private static final String AGENT = "TEXT2SQL";

    @BeforeEach
    void setUp() {
        traceStore = new TraceStore(traceRepo);
        when(traceRepo.save(any(AgentTrace.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ================================================================
    // 1. 5 种 Trace 记录
    // ================================================================

    @Nested
    @DisplayName("5 种 Trace 记录")
    class TraceRecording {

        @Test
        @DisplayName("recordThought 写入 THOUGHT 类型")
        void recordThought() {
            traceStore.recordThought(SESSION, AGENT, "开始分析慢SQL问题");

            ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
            verify(traceRepo).save(captor.capture());

            AgentTrace trace = captor.getValue();
            assertThat(trace.getSessionId()).isEqualTo(SESSION);
            assertThat(trace.getAgentName()).isEqualTo(AGENT);
            assertThat(trace.getStepType()).isEqualTo("THOUGHT");
            assertThat(trace.getContent()).isEqualTo("开始分析慢SQL问题");
            assertThat(trace.getStepIndex()).isEqualTo(1);
            assertThat(trace.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("recordAction 写入 ACTION 类型（含工具信息）")
        void recordAction() {
            Map<String, Object> input = Map.of("task", "查慢SQL", "attempt", 1);
            traceStore.recordAction(SESSION, AGENT, "LLM_GENERATE_SQL", input, "SELECT ...", true);

            ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
            verify(traceRepo).save(captor.capture());

            AgentTrace trace = captor.getValue();
            assertThat(trace.getStepType()).isEqualTo("ACTION");
            assertThat(trace.getToolName()).isEqualTo("LLM_GENERATE_SQL");
            assertThat(trace.getToolInput()).containsEntry("task", "查慢SQL");
            assertThat(trace.getToolOutput()).isEqualTo("SELECT ...");
            assertThat(trace.getToolSuccess()).isTrue();
            assertThat(trace.getContent()).contains("LLM_GENERATE_SQL");
        }

        @Test
        @DisplayName("recordObservation 写入 OBSERVATION 类型")
        void recordObservation() {
            traceStore.recordObservation(SESSION, AGENT, "查询成功: 5 行, 45ms");

            ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
            verify(traceRepo).save(captor.capture());
            assertThat(captor.getValue().getStepType()).isEqualTo("OBSERVATION");
        }

        @Test
        @DisplayName("recordReflection 写入 REFLECTION 类型")
        void recordReflection() {
            traceStore.recordReflection(SESSION, AGENT, "列名错误，建议改为 execution_time_ms");

            ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
            verify(traceRepo).save(captor.capture());
            assertThat(captor.getValue().getStepType()).isEqualTo("REFLECTION");
        }

        @Test
        @DisplayName("recordDecision 写入 DECISION 类型")
        void recordDecision() {
            traceStore.recordDecision(SESSION, AGENT, "诊断完成, 置信度 0.92");

            ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
            verify(traceRepo).save(captor.capture());
            assertThat(captor.getValue().getStepType()).isEqualTo("DECISION");
        }
    }

    // ================================================================
    // 2. 步骤编号
    // ================================================================

    @Nested
    @DisplayName("步骤编号")
    class StepIndex {

        @Test
        @DisplayName("同 session 步骤编号递增")
        void incrementWithinSession() {
            traceStore.recordThought(SESSION, "A", "thought1");
            traceStore.recordAction(SESSION, "B", "tool", Map.of(), "out", true);
            traceStore.recordObservation(SESSION, "A", "obs");

            ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
            verify(traceRepo, times(3)).save(captor.capture());

            List<AgentTrace> traces = captor.getAllValues();
            assertThat(traces.get(0).getStepIndex()).isEqualTo(1);
            assertThat(traces.get(1).getStepIndex()).isEqualTo(2);
            assertThat(traces.get(2).getStepIndex()).isEqualTo(3);
        }

        @Test
        @DisplayName("不同 session 步骤编号独立")
        void independentAcrossSessions() {
            traceStore.recordThought("s1", AGENT, "t1");
            traceStore.recordThought("s1", AGENT, "t2");
            traceStore.recordThought("s2", AGENT, "t1");

            ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
            verify(traceRepo, times(3)).save(captor.capture());

            List<AgentTrace> traces = captor.getAllValues();
            assertThat(traces.get(0).getStepIndex()).isEqualTo(1); // s1, step 1
            assertThat(traces.get(1).getStepIndex()).isEqualTo(2); // s1, step 2
            assertThat(traces.get(2).getStepIndex()).isEqualTo(1); // s2, step 1
        }
    }

    // ================================================================
    // 3. 监听器
    // ================================================================

    @Nested
    @DisplayName("实时监听器")
    class Listeners {

        @Test
        @DisplayName("注册监听器后每条 Trace 都会回调")
        void listenerNotified() {
            List<AgentTrace> received = new ArrayList<>();
            traceStore.addListener(SESSION, received::add);

            traceStore.recordThought(SESSION, AGENT, "t1");
            traceStore.recordAction(SESSION, AGENT, "tool", Map.of(), "out", true);

            assertThat(received).hasSize(2);
            assertThat(received.get(0).getStepType()).isEqualTo("THOUGHT");
            assertThat(received.get(1).getStepType()).isEqualTo("ACTION");
        }

        @Test
        @DisplayName("不同 session 的监听器互不影响")
        void listenersIsolated() {
            List<AgentTrace> received1 = new ArrayList<>();
            List<AgentTrace> received2 = new ArrayList<>();
            traceStore.addListener("s1", received1::add);
            traceStore.addListener("s2", received2::add);

            traceStore.recordThought("s1", AGENT, "t1");
            traceStore.recordThought("s2", AGENT, "t2");

            assertThat(received1).hasSize(1);
            assertThat(received2).hasSize(1);
            assertThat(received1.get(0).getContent()).isEqualTo("t1");
            assertThat(received2.get(0).getContent()).isEqualTo("t2");
        }

        @Test
        @DisplayName("移除单个监听器后不再回调")
        void removeListener() {
            List<AgentTrace> received = new ArrayList<>();
            TraceListener listener = received::add;
            traceStore.addListener(SESSION, listener);

            traceStore.recordThought(SESSION, AGENT, "before");
            assertThat(received).hasSize(1);

            traceStore.removeListener(SESSION, listener);
            traceStore.recordThought(SESSION, AGENT, "after");
            assertThat(received).hasSize(1); // 不再增加
        }

        @Test
        @DisplayName("removeListeners 清除所有监听器")
        void removeAllListeners() {
            List<AgentTrace> r1 = new ArrayList<>();
            List<AgentTrace> r2 = new ArrayList<>();
            traceStore.addListener(SESSION, r1::add);
            traceStore.addListener(SESSION, r2::add);

            traceStore.removeListeners(SESSION);
            traceStore.recordThought(SESSION, AGENT, "after");

            assertThat(r1).isEmpty();
            assertThat(r2).isEmpty();
        }

        @Test
        @DisplayName("监听器异常不影响持久化和其他监听器")
        void listenerExceptionIsolated() {
            List<AgentTrace> received = new ArrayList<>();
            // 第一个监听器抛异常
            traceStore.addListener(SESSION, t -> { throw new RuntimeException("boom"); });
            // 第二个正常
            traceStore.addListener(SESSION, received::add);

            traceStore.recordThought(SESSION, AGENT, "data");

            // DB 持久化正常
            verify(traceRepo).save(any(AgentTrace.class));
            // 第二个监听器正常收到
            assertThat(received).hasSize(1);
        }

        @Test
        @DisplayName("多线程并发写入 Trace 监听器线程安全")
        void concurrentWrites() throws InterruptedException {
            List<AgentTrace> received = new CopyOnWriteArrayList<>();
            traceStore.addListener(SESSION, received::add);

            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                new Thread(() -> {
                    traceStore.recordThought(SESSION, "AGENT_" + idx, "thought_" + idx);
                    latch.countDown();
                }).start();
            }

            latch.await(5, TimeUnit.SECONDS);
            assertThat(received).hasSize(threadCount);
        }
    }

    // ================================================================
    // 4. 查询方法
    // ================================================================

    @Nested
    @DisplayName("查询方法")
    class QueryMethods {

        @Test
        @DisplayName("getFullTrace 调用 repository")
        void getFullTrace() {
            List<AgentTrace> mockTraces = List.of(
                    AgentTrace.builder().sessionId(SESSION).stepIndex(1).stepType("THOUGHT")
                            .agentName(AGENT).content("t").createdAt(Instant.now()).build(),
                    AgentTrace.builder().sessionId(SESSION).stepIndex(2).stepType("ACTION")
                            .agentName(AGENT).content("a").createdAt(Instant.now()).build()
            );
            when(traceRepo.findBySessionIdOrderByStepIndex(SESSION)).thenReturn(mockTraces);

            List<AgentTrace> result = traceStore.getFullTrace(SESSION);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("getTimeline 转换为 TimelineItem 格式")
        void getTimeline() {
            List<AgentTrace> mockTraces = List.of(
                    AgentTrace.builder().sessionId(SESSION).stepIndex(1).stepType("THOUGHT")
                            .agentName(AGENT).content("思考").toolName(null)
                            .toolSuccess(null).latencyMs(null).createdAt(Instant.now()).build(),
                    AgentTrace.builder().sessionId(SESSION).stepIndex(2).stepType("ACTION")
                            .agentName(AGENT).content("调用工具: SQL_EXECUTE").toolName("SQL_EXECUTE")
                            .toolSuccess(true).latencyMs(45).createdAt(Instant.now()).build()
            );
            when(traceRepo.findBySessionIdOrderByStepIndex(SESSION)).thenReturn(mockTraces);

            List<TraceTimelineItem> timeline = traceStore.getTimeline(SESSION);

            assertThat(timeline).hasSize(2);
            assertThat(timeline.get(0).getStepType()).isEqualTo("THOUGHT");
            assertThat(timeline.get(0).getToolName()).isNull();
            assertThat(timeline.get(1).getToolName()).isEqualTo("SQL_EXECUTE");
            assertThat(timeline.get(1).getSuccess()).isTrue();
            assertThat(timeline.get(1).getLatencyMs()).isEqualTo(45);
        }

        @Test
        @DisplayName("getStats 正确统计各类型数量")
        void getStats() {
            List<AgentTrace> mockTraces = List.of(
                    buildTrace("THOUGHT", null),
                    buildTrace("ACTION", true),
                    buildTrace("ACTION", true),
                    buildTrace("ACTION", false),
                    buildTrace("OBSERVATION", null),
                    buildTrace("REFLECTION", null),
                    buildTrace("DECISION", null)
            );
            when(traceRepo.findBySessionIdOrderByStepIndex(SESSION)).thenReturn(mockTraces);

            TraceStore.TraceStats stats = traceStore.getStats(SESSION);

            assertThat(stats.totalSteps()).isEqualTo(7);
            assertThat(stats.thoughts()).isEqualTo(1);
            assertThat(stats.actions()).isEqualTo(3);
            assertThat(stats.observations()).isEqualTo(1);
            assertThat(stats.reflections()).isEqualTo(1);
            assertThat(stats.decisions()).isEqualTo(1);
            assertThat(stats.successActions()).isEqualTo(2);
            assertThat(stats.failedActions()).isEqualTo(1);
        }

        private AgentTrace buildTrace(String type, Boolean success) {
            return AgentTrace.builder()
                    .sessionId(SESSION).stepIndex(1).stepType(type)
                    .agentName(AGENT).content("c").toolSuccess(success)
                    .createdAt(Instant.now()).build();
        }
    }

    // ================================================================
    // 5. 异常处理
    // ================================================================

    @Nested
    @DisplayName("异常处理")
    class ExceptionHandling {

        @Test
        @DisplayName("DB 写入失败不抛异常")
        void dbFailureDoesNotThrow() {
            when(traceRepo.save(any())).thenThrow(new RuntimeException("DB down"));

            // 不应抛异常
            traceStore.recordThought(SESSION, AGENT, "data");

            verify(traceRepo).save(any());
        }

        @Test
        @DisplayName("DB 写入失败时监听器不回调")
        void dbFailureSkipsListeners() {
            when(traceRepo.save(any())).thenThrow(new RuntimeException("DB down"));

            List<AgentTrace> received = new ArrayList<>();
            traceStore.addListener(SESSION, received::add);

            traceStore.recordThought(SESSION, AGENT, "data");

            // 持久化失败时 notifyListeners 不执行
            assertThat(received).isEmpty();
        }
    }

    // ================================================================
    // 6. 清理
    // ================================================================

    @Nested
    @DisplayName("资源清理")
    class Cleanup {

        @Test
        @DisplayName("cleanupSession 清理计数器和监听器")
        void cleanupSession() {
            // 先写入一些数据建立计数器和监听器
            traceStore.recordThought(SESSION, AGENT, "t1");
            List<AgentTrace> received = new ArrayList<>();
            traceStore.addListener(SESSION, received::add);

            traceStore.cleanupSession(SESSION);

            // 计数器重置: 新 trace 应该从 1 开始
            traceStore.recordThought(SESSION, AGENT, "t2");
            ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
            verify(traceRepo, times(2)).save(captor.capture());
            assertThat(captor.getAllValues().get(1).getStepIndex()).isEqualTo(1);

            // 监听器已清除
            assertThat(received).isEmpty();
        }
    }
}
