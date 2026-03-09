package com.agentops.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlSanitizeResult {

    private boolean allowed;
    private String sanitizedSql;
    private String rejectReason;
    private List<String> tablesAccessed;

    public static SqlSanitizeResult allowed(String sanitizedSql, List<String> tables) {
        return SqlSanitizeResult.builder()
                .allowed(true)
                .sanitizedSql(sanitizedSql)
                .tablesAccessed(tables)
                .build();
    }

    public static SqlSanitizeResult rejected(String reason) {
        return SqlSanitizeResult.builder()
                .allowed(false)
                .rejectReason(reason)
                .build();
    }
}
