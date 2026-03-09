package com.agentops.tool;

import org.springframework.stereotype.Service;

/**
 * SQL AST 安全拦截器
 *
 * 基于 JSqlParser 解析 SQL 语法树:
 * 1. 白名单: 只允许 SELECT
 * 2. 黑名单: 禁止 DROP/TRUNCATE/ALTER/INSERT/UPDATE/DELETE
 * 3. 表名白名单: 只能查询监控相关的表
 * 4. LIMIT 限制
 */
@Service
public class SqlSanitizer {
    // TODO: 实现 SQL AST 安全拦截
}
