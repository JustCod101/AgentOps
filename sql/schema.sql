-- ============================================================
-- AgentOps 主库 Schema (agentops)
-- 存储诊断会话、Agent Trace、SQL 审计、知识库
-- ============================================================

-- 1. 诊断会话管理
CREATE TABLE IF NOT EXISTS diagnosis_session (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL UNIQUE,
    user_id         VARCHAR(64),
    title           VARCHAR(256),
    initial_query   TEXT NOT NULL,
    intent_type     VARCHAR(32),
    status          VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    root_cause      TEXT,
    fix_suggestion  TEXT,
    report_markdown TEXT,
    confidence      FLOAT,
    agent_count     INT DEFAULT 0,
    tool_call_count INT DEFAULT 0,
    retry_count     INT DEFAULT 0,
    total_tokens    INT DEFAULT 0,
    total_latency_ms BIGINT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_session_status ON diagnosis_session(status);
CREATE INDEX IF NOT EXISTS idx_session_intent ON diagnosis_session(intent_type);
CREATE INDEX IF NOT EXISTS idx_session_time   ON diagnosis_session(created_at DESC);

-- 2. Agent 执行 Trace
CREATE TABLE IF NOT EXISTS agent_trace (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL,
    agent_name      VARCHAR(64) NOT NULL,
    step_index      INT NOT NULL,
    step_type       VARCHAR(16) NOT NULL,
    content         TEXT NOT NULL,
    tool_name       VARCHAR(64),
    tool_input      JSONB,
    tool_output     TEXT,
    tool_success    BOOLEAN,
    latency_ms      INT,
    token_count     INT,
    state_before    VARCHAR(32),
    state_after     VARCHAR(32),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trace_session ON agent_trace(session_id, step_index);
CREATE INDEX IF NOT EXISTS idx_trace_agent   ON agent_trace(agent_name, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_trace_type    ON agent_trace(step_type);

-- 3. SQL 安全审计日志
CREATE TABLE IF NOT EXISTS sql_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL,
    original_sql    TEXT NOT NULL,
    sanitized_sql   TEXT,
    is_allowed      BOOLEAN NOT NULL,
    reject_reason   VARCHAR(256),
    sql_type        VARCHAR(16),
    tables_accessed JSONB,
    has_subquery    BOOLEAN DEFAULT FALSE,
    has_join        BOOLEAN DEFAULT FALSE,
    estimated_rows  BIGINT,
    executed        BOOLEAN DEFAULT FALSE,
    execution_ms    INT,
    result_rows     INT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sql_audit_session ON sql_audit_log(session_id);
CREATE INDEX IF NOT EXISTS idx_sql_audit_allowed ON sql_audit_log(is_allowed);

-- 4. 运维知识库
CREATE TABLE IF NOT EXISTS knowledge_entry (
    id              BIGSERIAL PRIMARY KEY,
    category        VARCHAR(64) NOT NULL,
    title           VARCHAR(256) NOT NULL,
    content         TEXT NOT NULL,
    tags            JSONB DEFAULT '[]',
    match_patterns  JSONB DEFAULT '[]',
    priority        INT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_category ON knowledge_entry(category);

-- ============================================================
-- 知识库初始数据
-- ============================================================

INSERT INTO knowledge_entry (category, title, content, tags, match_patterns, priority) VALUES
('SLOW_QUERY_PATTERN', '全表扫描导致慢查询',
 '当查询未命中索引时会触发全表扫描，表现为 rows_examined 远大于 rows_sent。\n修复方案：\n1. 检查 WHERE 条件字段是否有索引\n2. 使用 EXPLAIN 分析执行计划\n3. 添加合适的复合索引\n4. 避免在索引列上使用函数',
 '["慢查询", "全表扫描", "索引"]', '["full table scan", "seq scan", "rows_examined"]', 10),

('SLOW_QUERY_PATTERN', '锁等待导致查询超时',
 '当多个事务竞争同一行/表锁时，后续事务会阻塞等待。\n诊断方法：\n1. 检查 lock_time_ms 是否异常偏高\n2. 查看 pg_locks 视图确认锁冲突\n3. 检查是否有长事务未提交\n修复方案：\n1. 优化事务粒度，缩短持锁时间\n2. 调整事务隔离级别\n3. 使用 NOWAIT 或 SKIP LOCKED',
 '["锁等待", "死锁", "事务"]', '["lock_time", "waiting", "deadlock", "lock timeout"]', 9),

('ERROR_CODE', 'Connection pool exhausted',
 '连接池耗尽通常由以下原因引起：\n1. 慢查询占用连接时间过长\n2. 连接泄漏（未正确关闭）\n3. 突发流量超过连接池上限\n修复方案：\n1. 增大 max_connections（临时）\n2. 排查并优化慢查询（根本）\n3. 检查应用层连接释放逻辑\n4. 配置连接池空闲回收策略',
 '["连接池", "连接数"]', '["connection pool", "too many connections", "connection exhausted"]', 10),

('BEST_PRACTICE', '数据库连接数监控阈值',
 '建议阈值设置：\n- active_connections / max_connections > 70%：WARNING\n- active_connections / max_connections > 85%：CRITICAL\n- waiting_connections > 0 且持续超过30秒：CRITICAL\n- idle_connections < 2：WARNING（连接池可能配置过小）',
 '["连接数", "阈值", "监控"]', '["connection", "threshold"]', 5),

('RUNBOOK', '数据库响应变慢标准排障流程',
 '1. 检查慢查询日志：是否有新增慢SQL或执行时间突增\n2. 检查连接状态：active/waiting 连接数是否异常\n3. 检查系统指标：CPU、内存、磁盘IO\n4. 检查锁等待：是否有长事务导致锁阻塞\n5. 检查服务日志：上游服务是否有超时报错\n6. 检查最近变更：是否有新上线的SQL或配置变更\n\n常见根因：\n- 缺失索引导致全表扫描\n- 大事务持锁导致连接排队\n- 连接池配置不合理\n- 数据量增长超过预期',
 '["排障", "慢查询", "runbook"]', '["响应慢", "延迟高", "slow", "latency"]', 10);
