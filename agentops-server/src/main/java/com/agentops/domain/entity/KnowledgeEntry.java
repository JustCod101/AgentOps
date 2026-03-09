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
@Table(name = "knowledge_entry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> tags = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "match_patterns", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> matchPatterns = List.of();

    @Builder.Default
    private Integer priority = 0;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
