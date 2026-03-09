package com.agentops.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "agent_trace")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "agent_name", nullable = false, length = 64)
    private String agentName;

    @Column(name = "step_index", nullable = false)
    private Integer stepIndex;

    @Column(name = "step_type", nullable = false, length = 16)
    private String stepType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "tool_name", length = 64)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_input", columnDefinition = "jsonb")
    private Map<String, Object> toolInput;

    @Column(name = "tool_output", columnDefinition = "TEXT")
    private String toolOutput;

    @Column(name = "tool_success")
    private Boolean toolSuccess;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "state_before", length = 32)
    private String stateBefore;

    @Column(name = "state_after", length = 32)
    private String stateAfter;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
