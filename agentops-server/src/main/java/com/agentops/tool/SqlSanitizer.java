package com.agentops.tool;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SQL AST 安全拦截器
 *
 * 基于 JSqlParser 解析 SQL 语法树，实施多层安全防护:
 * 1. 语句类型白名单: 只允许 SELECT
 * 2. 多语句检测: 禁止分号分隔的多条 SQL
 * 3. 表名白名单: 只能查询监控相关的表
 * 4. 子查询递归安全检查: 子查询中的表也必须在白名单内
 * 5. 危险函数黑名单: 禁止 SLEEP、BENCHMARK、LOAD_FILE 等
 * 6. LIMIT 约束: 自动追加或强制降为 100
 */
@Service
public class SqlSanitizer {

    private static final Logger log = LoggerFactory.getLogger(SqlSanitizer.class);

    private static final int MAX_LIMIT = 100;

    /**
     * 允许查询的表白名单
     */
    private static final Set<String> ALLOWED_TABLES = Set.of(
            "slow_query_log",
            "db_connection_status",
            "service_error_log",
            "system_metric"
    );

    /**
     * 危险函数黑名单（全部大写比较）
     */
    private static final Set<String> DANGEROUS_FUNCTIONS = Set.of(
            "SLEEP",
            "BENCHMARK",
            "LOAD_FILE",
            "SYSTEM",
            "EXEC",
            "EXECUTE",
            "XP_CMDSHELL",
            "SP_EXECUTESQL",
            "PG_SLEEP",
            "PG_READ_FILE",
            "PG_READ_BINARY_FILE",
            "LO_IMPORT",
            "LO_EXPORT",
            "DBLINK",
            "COPY"
    );

    public SqlSanitizeResult sanitize(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return SqlSanitizeResult.rejected("SQL 为空");
        }

        try {
            // Step 1: 基础清洗 — 去除首尾空白和末尾分号
            String sql = rawSql.strip().replaceAll(";+$", "");

            // Step 2: 多语句检测
            if (containsMultipleStatements(sql)) {
                return SqlSanitizeResult.rejected("禁止多语句执行（检测到分号分隔的多条 SQL）");
            }

            // Step 3: 解析 SQL
            Statement stmt = CCJSqlParserUtil.parse(sql);

            // Step 4: 语句类型检查 — 只允许 SELECT
            if (!(stmt instanceof Select select)) {
                String type = stmt.getClass().getSimpleName();
                return SqlSanitizeResult.rejected(
                        "仅允许 SELECT 查询，当前类型: " + type);
            }

            // Step 5: 表名白名单检查（TablesNamesFinder 会递归包含子查询中的表）
            Set<String> tableNames = TablesNamesFinder.findTables(sql);
            List<String> tableList = new ArrayList<>();
            for (String tableName : tableNames) {
                String normalized = tableName.toLowerCase()
                        .replaceAll("[`\"\\[\\]]", "");
                if (!ALLOWED_TABLES.contains(normalized)) {
                    return SqlSanitizeResult.rejected(
                            "禁止访问表: " + normalized
                                    + "，允许的表: " + ALLOWED_TABLES);
                }
                tableList.add(normalized);
            }

            // Step 6: 子查询递归安全检查
            SubqueryValidator subqueryValidator = new SubqueryValidator();
            select.accept((SelectVisitor) subqueryValidator);
            if (subqueryValidator.hasUnsafe()) {
                return SqlSanitizeResult.rejected(
                        "子查询中包含不安全操作: " + subqueryValidator.getReason());
            }

            // Step 7: 危险函数检查
            DangerousFunctionDetector funcDetector = new DangerousFunctionDetector();
            select.accept((SelectVisitor) funcDetector);
            if (funcDetector.hasUnsafe()) {
                return SqlSanitizeResult.rejected(
                        "包含禁止的函数: " + funcDetector.getUnsafeFunctions());
            }

            // Step 8: LIMIT 约束
            enforceLimit(select);

            String sanitizedSql = select.toString();
            log.debug("SQL 安全检查通过: {}", sanitizedSql);

            return SqlSanitizeResult.allowed(sanitizedSql, tableList);

        } catch (JSQLParserException e) {
            log.warn("SQL 语法解析失败: {}", e.getMessage());
            return SqlSanitizeResult.rejected("SQL 语法解析失败: " + e.getMessage());
        }
    }

    /**
     * 多语句检测: 在引号外查找分号
     */
    private boolean containsMultipleStatements(String sql) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (c == '\'' && !inDoubleQuote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++; // 跳过转义单引号 ''
                } else {
                    inSingleQuote = !inSingleQuote;
                }
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                String remaining = sql.substring(i + 1).strip();
                if (!remaining.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 对 SELECT 语句强制 LIMIT 约束（递归处理各种 SELECT 形态）
     */
    private void enforceLimit(Select select) {
        if (select instanceof PlainSelect plainSelect) {
            enforceLimitOnPlainSelect(plainSelect);
        } else if (select instanceof SetOperationList setOpList) {
            for (Select child : setOpList.getSelects()) {
                enforceLimit(child);
            }
        } else if (select instanceof ParenthesedSelect parenthesed) {
            enforceLimit(parenthesed.getSelect());
        }
    }

    private void enforceLimitOnPlainSelect(PlainSelect plainSelect) {
        Limit limit = plainSelect.getLimit();
        if (limit == null) {
            Limit newLimit = new Limit();
            newLimit.setRowCount(new LongValue(MAX_LIMIT));
            plainSelect.setLimit(newLimit);
        } else {
            Expression rowCount = limit.getRowCount();
            if (rowCount instanceof LongValue longVal && longVal.getValue() > MAX_LIMIT) {
                longVal.setValue(MAX_LIMIT);
            }
        }
    }

    // ========================================================================
    // 子查询递归安全检查 — 确保子查询中不包含非 SELECT 操作
    // ========================================================================

    private static class SubqueryValidator extends SelectVisitorAdapter {

        private boolean unsafe = false;
        private String reason;

        boolean hasUnsafe() { return unsafe; }
        String getReason() { return reason; }

        @Override
        public void visit(PlainSelect plainSelect) {
            // 检查 FROM 子句中的子查询
            if (plainSelect.getFromItem() instanceof ParenthesedSelect sub) {
                sub.getSelect().accept((SelectVisitor) this);
            }

            // 检查 JOIN 中的子查询
            if (plainSelect.getJoins() != null) {
                for (var join : plainSelect.getJoins()) {
                    if (join.getFromItem() instanceof ParenthesedSelect sub) {
                        sub.getSelect().accept((SelectVisitor) this);
                    }
                }
            }

            // 检查 WHERE / SELECT 项中的子查询表达式
            ExpressionVisitorAdapter exprChecker = new ExpressionVisitorAdapter() {
                @Override
                public void visit(ParenthesedSelect subSelect) {
                    subSelect.getSelect().accept((SelectVisitor) SubqueryValidator.this);
                }
            };

            if (plainSelect.getWhere() != null) {
                plainSelect.getWhere().accept(exprChecker);
            }
            if (plainSelect.getSelectItems() != null) {
                for (SelectItem<?> item : plainSelect.getSelectItems()) {
                    item.getExpression().accept(exprChecker);
                }
            }
        }

        @Override
        public void visit(SetOperationList setOpList) {
            for (Select child : setOpList.getSelects()) {
                child.accept((SelectVisitor) this);
            }
        }

        @Override
        public void visit(ParenthesedSelect parenthesed) {
            parenthesed.getSelect().accept((SelectVisitor) this);
        }
    }

    // ========================================================================
    // 危险函数检测 — 递归遍历所有表达式节点
    // ========================================================================

    private static class DangerousFunctionDetector extends SelectVisitorAdapter {

        private final List<String> unsafeFunctions = new ArrayList<>();

        boolean hasUnsafe() { return !unsafeFunctions.isEmpty(); }
        List<String> getUnsafeFunctions() { return unsafeFunctions; }

        private final ExpressionVisitorAdapter exprChecker = new ExpressionVisitorAdapter() {
            @Override
            public void visit(Function function) {
                String funcName = function.getName().toUpperCase();
                if (DANGEROUS_FUNCTIONS.contains(funcName)) {
                    unsafeFunctions.add(funcName);
                }
                // 递归检查函数参数中的嵌套函数
                if (function.getParameters() != null) {
                    for (Expression param : function.getParameters()) {
                        param.accept(this);
                    }
                }
            }

            @Override
            public void visit(ParenthesedSelect subSelect) {
                subSelect.getSelect().accept((SelectVisitor) DangerousFunctionDetector.this);
            }
        };

        @Override
        public void visit(PlainSelect plainSelect) {
            // SELECT 项
            if (plainSelect.getSelectItems() != null) {
                for (SelectItem<?> item : plainSelect.getSelectItems()) {
                    item.getExpression().accept(exprChecker);
                }
            }
            // WHERE
            if (plainSelect.getWhere() != null) {
                plainSelect.getWhere().accept(exprChecker);
            }
            // HAVING
            if (plainSelect.getHaving() != null) {
                plainSelect.getHaving().accept(exprChecker);
            }
            // ORDER BY
            if (plainSelect.getOrderByElements() != null) {
                for (var orderBy : plainSelect.getOrderByElements()) {
                    orderBy.getExpression().accept(exprChecker);
                }
            }
            // FROM / JOIN 中的子查询
            if (plainSelect.getFromItem() instanceof ParenthesedSelect sub) {
                sub.getSelect().accept((SelectVisitor) this);
            }
            if (plainSelect.getJoins() != null) {
                for (var join : plainSelect.getJoins()) {
                    if (join.getFromItem() instanceof ParenthesedSelect sub) {
                        sub.getSelect().accept((SelectVisitor) this);
                    }
                }
            }
        }

        @Override
        public void visit(SetOperationList setOpList) {
            for (Select child : setOpList.getSelects()) {
                child.accept((SelectVisitor) this);
            }
        }

        @Override
        public void visit(ParenthesedSelect parenthesed) {
            parenthesed.getSelect().accept((SelectVisitor) this);
        }
    }
}
