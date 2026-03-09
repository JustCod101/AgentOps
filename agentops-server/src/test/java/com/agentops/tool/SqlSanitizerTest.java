package com.agentops.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlSanitizerTest {

    private SqlSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new SqlSanitizer();
    }

    // =====================================================================
    // 正常放行
    // =====================================================================

    @Nested
    @DisplayName("正常 SELECT 放行")
    class AllowedQueries {

        @Test
        @DisplayName("简单 SELECT 通过")
        void simpleSelect() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log WHERE created_at > NOW() - INTERVAL '10 minutes' LIMIT 20");
            assertTrue(result.isAllowed());
            assertNotNull(result.getSanitizedSql());
            assertTrue(result.getTablesAccessed().contains("slow_query_log"));
        }

        @Test
        @DisplayName("多表 JOIN 通过")
        void joinQuery() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT s.query_text, m.value FROM slow_query_log s "
                            + "JOIN system_metric m ON s.created_at = m.sampled_at "
                            + "LIMIT 50");
            assertTrue(result.isAllowed());
            assertEquals(2, result.getTablesAccessed().size());
        }

        @Test
        @DisplayName("聚合函数通过")
        void aggregateQuery() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT COUNT(*), AVG(execution_time_ms), MAX(execution_time_ms) "
                            + "FROM slow_query_log WHERE created_at > NOW() - INTERVAL '1 hour'");
            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("子查询中访问白名单表 — 通过")
        void subqueryWithAllowedTable() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log WHERE execution_time_ms > "
                            + "(SELECT AVG(execution_time_ms) FROM slow_query_log) LIMIT 10");
            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("末尾分号被清除后正常解析")
        void trailingSemicolon() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM system_metric LIMIT 10;");
            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("所有4张白名单表都可查询")
        void allAllowedTables() {
            for (String table : new String[]{
                    "slow_query_log", "db_connection_status",
                    "service_error_log", "system_metric"}) {
                SqlSanitizeResult result = sanitizer.sanitize(
                        "SELECT * FROM " + table + " LIMIT 5");
                assertTrue(result.isAllowed(), "应放行表: " + table);
            }
        }
    }

    // =====================================================================
    // LIMIT 约束
    // =====================================================================

    @Nested
    @DisplayName("LIMIT 约束")
    class LimitEnforcement {

        @Test
        @DisplayName("无 LIMIT → 自动追加 LIMIT 100")
        void autoAppendLimit() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log");
            assertTrue(result.isAllowed());
            assertTrue(result.getSanitizedSql().toUpperCase().contains("LIMIT 100"),
                    "应自动追加 LIMIT 100, 实际: " + result.getSanitizedSql());
        }

        @Test
        @DisplayName("LIMIT 200 → 降为 LIMIT 100")
        void reduceLargeLimit() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log LIMIT 200");
            assertTrue(result.isAllowed());
            assertTrue(result.getSanitizedSql().toUpperCase().contains("LIMIT 100"),
                    "应降为 LIMIT 100, 实际: " + result.getSanitizedSql());
        }

        @Test
        @DisplayName("LIMIT 50 → 保持不变")
        void keepSmallLimit() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log LIMIT 50");
            assertTrue(result.isAllowed());
            assertTrue(result.getSanitizedSql().toUpperCase().contains("LIMIT 50"),
                    "应保持 LIMIT 50, 实际: " + result.getSanitizedSql());
        }

        @Test
        @DisplayName("LIMIT 100 → 保持不变")
        void keepExactLimit() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log LIMIT 100");
            assertTrue(result.isAllowed());
            assertTrue(result.getSanitizedSql().toUpperCase().contains("LIMIT 100"));
        }
    }

    // =====================================================================
    // 语句类型拦截
    // =====================================================================

    @Nested
    @DisplayName("非 SELECT 语句拦截")
    class StatementTypeBlocking {

        @Test
        @DisplayName("INSERT 被拦截")
        void blockInsert() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "INSERT INTO slow_query_log (query_text) VALUES ('test')");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("仅允许 SELECT"));
        }

        @Test
        @DisplayName("UPDATE 被拦截")
        void blockUpdate() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "UPDATE slow_query_log SET query_text = 'hacked' WHERE id = 1");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("仅允许 SELECT"));
        }

        @Test
        @DisplayName("DELETE 被拦截")
        void blockDelete() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "DELETE FROM slow_query_log WHERE id = 1");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("仅允许 SELECT"));
        }

        @Test
        @DisplayName("DROP TABLE 被拦截")
        void blockDrop() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "DROP TABLE slow_query_log");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("仅允许 SELECT"));
        }

        @Test
        @DisplayName("TRUNCATE 被拦截")
        void blockTruncate() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "TRUNCATE TABLE slow_query_log");
            assertFalse(result.isAllowed());
        }

        @Test
        @DisplayName("ALTER TABLE 被拦截")
        void blockAlter() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "ALTER TABLE slow_query_log ADD COLUMN hacked TEXT");
            assertFalse(result.isAllowed());
        }
    }

    // =====================================================================
    // 表名白名单拦截
    // =====================================================================

    @Nested
    @DisplayName("表名白名单拦截")
    class TableWhitelist {

        @Test
        @DisplayName("非白名单表被拦截")
        void blockNonWhitelistedTable() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM users LIMIT 10");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("禁止访问表"));
            assertTrue(result.getRejectReason().contains("users"));
        }

        @Test
        @DisplayName("JOIN 中包含非白名单表被拦截")
        void blockNonWhitelistedTableInJoin() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log s JOIN users u ON s.id = u.id LIMIT 10");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("users"));
        }

        @Test
        @DisplayName("子查询中非白名单表被拦截")
        void blockNonWhitelistedTableInSubquery() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log WHERE id IN "
                            + "(SELECT id FROM secret_table) LIMIT 10");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("secret_table"));
        }
    }

    // =====================================================================
    // 多语句检测
    // =====================================================================

    @Nested
    @DisplayName("多语句检测")
    class MultiStatementDetection {

        @Test
        @DisplayName("分号分隔的两条 SQL 被拦截")
        void blockMultiStatements() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log; DROP TABLE slow_query_log");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("多语句"));
        }

        @Test
        @DisplayName("字符串内的分号不误判")
        void allowSemicolonInString() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log WHERE query_text = 'SELECT 1; DROP TABLE x' LIMIT 10");
            assertTrue(result.isAllowed(),
                    "字符串内的分号不应触发多语句检测, reason: " + result.getRejectReason());
        }

        @Test
        @DisplayName("SQL 注入尝试: UNION + 写操作")
        void blockInjectionAttempt() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log; INSERT INTO slow_query_log VALUES (1,'hacked')");
            assertFalse(result.isAllowed());
        }
    }

    // =====================================================================
    // 危险函数拦截
    // =====================================================================

    @Nested
    @DisplayName("危险函数拦截")
    class DangerousFunctions {

        @Test
        @DisplayName("SLEEP 函数被拦截")
        void blockSleep() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT SLEEP(10) FROM slow_query_log LIMIT 1");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("SLEEP"));
        }

        @Test
        @DisplayName("pg_sleep 函数被拦截")
        void blockPgSleep() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT pg_sleep(10) FROM slow_query_log LIMIT 1");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("PG_SLEEP"));
        }

        @Test
        @DisplayName("BENCHMARK 函数被拦截")
        void blockBenchmark() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT BENCHMARK(10000000, SHA1('test')) FROM slow_query_log LIMIT 1");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("BENCHMARK"));
        }

        @Test
        @DisplayName("LOAD_FILE 函数被拦截")
        void blockLoadFile() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT LOAD_FILE('/etc/passwd') FROM slow_query_log LIMIT 1");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("LOAD_FILE"));
        }

        @Test
        @DisplayName("WHERE 子句中的危险函数也被拦截")
        void blockDangerousFunctionInWhere() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM slow_query_log WHERE SLEEP(5) = 0 LIMIT 1");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("SLEEP"));
        }

        @Test
        @DisplayName("安全函数 (COUNT/AVG/MAX) 放行")
        void allowSafeFunctions() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT COUNT(*), AVG(execution_time_ms), MAX(rows_examined) "
                            + "FROM slow_query_log LIMIT 10");
            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("嵌套函数中的危险函数被拦截")
        void blockNestedDangerousFunction() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT COALESCE(SLEEP(5), 0) FROM slow_query_log LIMIT 1");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("SLEEP"));
        }
    }

    // =====================================================================
    // 边界情况
    // =====================================================================

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("空字符串被拒绝")
        void emptyString() {
            SqlSanitizeResult result = sanitizer.sanitize("");
            assertFalse(result.isAllowed());
        }

        @Test
        @DisplayName("null 被拒绝")
        void nullInput() {
            SqlSanitizeResult result = sanitizer.sanitize(null);
            assertFalse(result.isAllowed());
        }

        @Test
        @DisplayName("非法 SQL 语法被拒绝")
        void invalidSqlSyntax() {
            SqlSanitizeResult result = sanitizer.sanitize("THIS IS NOT SQL");
            assertFalse(result.isAllowed());
            assertTrue(result.getRejectReason().contains("解析失败"));
        }

        @Test
        @DisplayName("带引号的表名正确处理")
        void quotedTableName() {
            SqlSanitizeResult result = sanitizer.sanitize(
                    "SELECT * FROM \"slow_query_log\" LIMIT 10");
            assertTrue(result.isAllowed(),
                    "带引号的白名单表应放行, reason: " + result.getRejectReason());
        }

        @Test
        @DisplayName("复杂实际查询: 模拟 Agent 生成的 SQL")
        void realisticAgentQuery() {
            String sql = """
                    SELECT query_text, execution_time_ms, rows_examined, rows_sent, lock_time_ms
                    FROM slow_query_log
                    WHERE created_at > NOW() - INTERVAL '10 minutes'
                    AND execution_time_ms > 1000
                    ORDER BY execution_time_ms DESC
                    LIMIT 20""";
            SqlSanitizeResult result = sanitizer.sanitize(sql);
            assertTrue(result.isAllowed());
            assertTrue(result.getTablesAccessed().contains("slow_query_log"));
        }

        @Test
        @DisplayName("GROUP BY + HAVING 查询通过")
        void groupByHaving() {
            String sql = """
                    SELECT service_name, COUNT(*) as error_count
                    FROM service_error_log
                    WHERE log_level = 'ERROR'
                    AND created_at > NOW() - INTERVAL '30 minutes'
                    GROUP BY service_name
                    HAVING COUNT(*) > 5
                    ORDER BY error_count DESC""";
            SqlSanitizeResult result = sanitizer.sanitize(sql);
            assertTrue(result.isAllowed());
            // 应自动加 LIMIT 100
            assertTrue(result.getSanitizedSql().toUpperCase().contains("LIMIT 100"));
        }
    }
}
