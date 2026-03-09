package com.agentops.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "diagnosis_session")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true, length = 36)
    private String sessionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(length = 256)
    private String title;

    @Column(name = "initial_query", nullable = false, columnDefinition = "TEXT")
    private String initialQuery;

    @Column(name = "intent_type", length = 32)
    private String intentType;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "CREATED";

    @Column(name = "root_cause", columnDefinition = "TEXT")
    private String rootCause;

    @Column(name = "fix_suggestion", columnDefinition = "TEXT")
    private String fixSuggestion;

    @Column(name = "report_markdown", columnDefinition = "TEXT")
    private String reportMarkdown;

    private Float confidence;

    @Column(name = "agent_count")
    @Builder.Default
    private Integer agentCount = 0;

    @Column(name = "tool_call_count")
    @Builder.Default
    private Integer toolCallCount = 0;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "total_tokens")
    @Builder.Default
    private Integer totalTokens = 0;

    @Column(name = "total_latency_ms")
    @Builder.Default
    private Long totalLatencyMs = 0L;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}
