package com.agentops.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "sql_audit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "original_sql", nullable = false, columnDefinition = "TEXT")
    private String originalSql;

    @Column(name = "sanitized_sql", columnDefinition = "TEXT")
    private String sanitizedSql;

    @Column(name = "is_allowed", nullable = false)
    private Boolean isAllowed;

    @Column(name = "reject_reason", length = 256)
    private String rejectReason;

    @Column(name = "sql_type", length = 16)
    private String sqlType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tables_accessed", columnDefinition = "jsonb")
    private List<String> tablesAccessed;

    @Column(name = "has_subquery")
    @Builder.Default
    private Boolean hasSubquery = false;

    @Column(name = "has_join")
    @Builder.Default
    private Boolean hasJoin = false;

    @Column(name = "estimated_rows")
    private Long estimatedRows;

    @Builder.Default
    private Boolean executed = false;

    @Column(name = "execution_ms")
    private Integer executionMs;

    @Column(name = "result_rows")
    private Integer resultRows;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
