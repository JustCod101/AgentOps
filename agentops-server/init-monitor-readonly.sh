#!/bin/sh
# 创建只读用户 — 供 AgentOps Agent 查询监控数据
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER readonly_user WITH PASSWORD '${READONLY_PASSWORD:-readonly123}';
    GRANT CONNECT ON DATABASE monitor TO readonly_user;
    GRANT USAGE ON SCHEMA public TO readonly_user;
    GRANT SELECT ON ALL TABLES IN SCHEMA public TO readonly_user;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO readonly_user;
EOSQL
