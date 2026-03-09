-- ============================================================
-- 监控数据源 Schema (monitor)
-- 模拟生产环境的监控数据，Agent 查询目标
-- ============================================================

-- 1. 慢查询记录表
CREATE TABLE IF NOT EXISTS slow_query_log (
    id                BIGSERIAL PRIMARY KEY,
    query_text        TEXT NOT NULL,
    query_hash        VARCHAR(64),
    execution_time_ms BIGINT NOT NULL,
    rows_examined     BIGINT,
    rows_sent         BIGINT,
    lock_time_ms      BIGINT DEFAULT 0,
    db_name           VARCHAR(64),
    user_host         VARCHAR(128),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_slow_query_time     ON slow_query_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_slow_query_duration ON slow_query_log(execution_time_ms DESC);

-- 2. 数据库连接状态表
CREATE TABLE IF NOT EXISTS db_connection_status (
    id                  BIGSERIAL PRIMARY KEY,
    total_connections   INT NOT NULL,
    active_connections  INT NOT NULL,
    idle_connections    INT NOT NULL,
    waiting_connections INT NOT NULL,
    max_connections     INT NOT NULL,
    sampled_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_conn_status_time ON db_connection_status(sampled_at DESC);

-- 3. 微服务错误日志表
CREATE TABLE IF NOT EXISTS service_error_log (
    id              BIGSERIAL PRIMARY KEY,
    service_name    VARCHAR(64) NOT NULL,
    log_level       VARCHAR(8) NOT NULL,
    message         TEXT NOT NULL,
    stack_trace     TEXT,
    trace_id        VARCHAR(36),
    span_id         VARCHAR(16),
    host            VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_error_log_service ON service_error_log(service_name, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_error_log_level   ON service_error_log(log_level, created_at DESC);

-- 4. 系统指标表
CREATE TABLE IF NOT EXISTS system_metric (
    id              BIGSERIAL PRIMARY KEY,
    metric_name     VARCHAR(128) NOT NULL,
    service_name    VARCHAR(64),
    value           FLOAT NOT NULL,
    labels          JSONB,
    sampled_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_metric_name    ON system_metric(metric_name, sampled_at DESC);
CREATE INDEX IF NOT EXISTS idx_metric_service ON system_metric(service_name, sampled_at DESC);
