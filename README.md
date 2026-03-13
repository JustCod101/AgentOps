# AgentOps — 多智能体协同 AIOps 排障专家系统

基于 **SpringBoot 3.2 + LangChain4j + MiniMax-M2.5** 的智能运维排障系统。通过多 Agent 协同（Router-Worker 架构），实现自然语言驱动的自动化故障诊断。

## 项目概览

```
AgentOps/
├── agentops-server/        # 后端服务 (SpringBoot)
├── sql/                    # 数据库 Schema & 种子数据
│   ├── schema.sql              # 主库 Schema (诊断会话/Trace/审计/知识库)
│   ├── monitor-schema.sql      # 监控库 Schema (慢查询/连接/日志/指标)
│   └── seed-data.sql           # 模拟故障场景数据
├── docs/                   # 设计文档
└── e2e_test.py             # 端到端集成测试
```

## 生产环境部署指南

### 前置条件

- Linux 服务器 (推荐 Ubuntu 22.04+ / CentOS 8+)
- Docker 24+ & Docker Compose v2
- 至少 4GB RAM, 2 核 CPU
- MiniMax API Key（或任何 OpenAI 兼容 API）

### 第一步：准备配置

```bash
git clone https://github.com/JustCod101/AgentOps.git
cd AgentOps/agentops-server
```

创建 `.env` 文件:

```env
# ========== LLM 配置 ==========
OPENAI_API_KEY=your-minimax-api-key
OPENAI_BASE_URL=https://api.minimax.chat/v1

# ========== 数据库密码 ==========
DB_PASSWORD=<生产环境强密码>
READONLY_PASSWORD=<生产环境强密码>

# ========== 可选: JVM 调优 ==========
# JAVA_OPTS=-Xms1g -Xmx2g -XX:+UseG1GC
```

### 第二步：启动服务

```bash
# 构建并启动所有服务
docker compose up -d --build

# 查看启动日志
docker compose logs -f app
```

启动后会自动:
1. 创建 PostgreSQL 主库 (`agentops`) 并初始化 Schema
2. 创建 PostgreSQL 监控库 (`monitor`) 并注入模拟故障数据
3. 创建监控库只读用户 (`readonly_user`)
4. 启动 Redis
5. 构建并启动 AgentOps Server

### 第三步：验证部署

```bash
# 健康检查
curl http://localhost:8080/actuator/health

# 发起一次测试诊断
curl -N -X POST http://localhost:8080/api/v1/diagnosis/stream \
  -H "Content-Type: application/json" \
  -d '{"query": "数据库响应变慢，最近10分钟有大量超时"}'
```

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| AgentOps Server | 8080 | REST API + SSE |
| PostgreSQL 主库 | 5432 | 诊断数据存储 |
| PostgreSQL 监控库 | 5433 | 被监控目标数据源 |
| Redis | 6379 | 缓存 & 会话 |

---

## 生产环境最佳实践

### 1. 接入真实监控数据源

默认的 `monitor-db` 使用模拟数据。生产环境中，你应该将 Agent 指向真实的监控数据源:

```env
# .env 中修改为真实监控库地址
MONITOR_DB_URL=jdbc:postgresql://your-real-monitor-db:5432/monitor
READONLY_PASSWORD=your-readonly-password
```

确保监控库中存在以下 4 张表（参考 `sql/monitor-schema.sql`）:
- `slow_query_log` — 慢查询记录
- `db_connection_status` — 连接池状态
- `service_error_log` — 微服务错误日志
- `system_metric` — 系统指标 (CPU/内存/QPS/P99 等)

### 2. 安全加固

```env
# 使用强密码
DB_PASSWORD=$(openssl rand -base64 32)
READONLY_PASSWORD=$(openssl rand -base64 32)
```

生产环境建议:
- 不要将 5432/5433/6379 端口暴露到公网，仅在 Docker 内网通信
- 在 `docker-compose.yml` 中移除数据库的 `ports` 映射，仅保留 `app` 的 8080
- 在 app 前端部署 Nginx 反向代理，配置 HTTPS
- 配置 API 鉴权（可在 `GlobalExceptionHandler` 前加 Security Filter）

修改 `docker-compose.yml` 关闭数据库外部端口:

```yaml
postgres:
  # ports:            # 注释掉，不暴露到宿主机
  #   - "5432:5432"

monitor-db:
  # ports:
  #   - "5433:5432"

redis:
  # ports:
  #   - "6379:6379"
  command: redis-server --appendonly yes --requirepass ${REDIS_PASSWORD}
```

### 3. 日志 & 监控

```bash
# 查看实时日志
docker compose logs -f app

# 查看特定服务日志
docker compose logs -f postgres

# 导出日志到文件
docker compose logs app > /var/log/agentops/app.log 2>&1
```

建议配置日志持久化:
```yaml
# docker-compose.yml 中为 app 添加日志卷
app:
  volumes:
    - ./logs:/app/logs
  environment:
    JAVA_OPTS: "-Xms1g -Xmx2g -Dlogging.file.path=/app/logs"
```

### 4. 数据备份

```bash
# 备份主库
docker exec agentops-postgres pg_dump -U admin agentops > backup_$(date +%Y%m%d).sql

# 定时备份 (crontab)
0 2 * * * docker exec agentops-postgres pg_dump -U admin agentops | gzip > /backup/agentops_$(date +\%Y\%m\%d).sql.gz
```

### 5. 扩展知识库

通过 API 添加运维知识，提升诊断准确率:

```bash
# 添加知识条目
curl -X POST http://localhost:8080/api/v1/knowledge \
  -H "Content-Type: application/json" \
  -d '{
    "category": "SLOW_QUERY_PATTERN",
    "title": "分区表跨分区查询导致慢查询",
    "content": "当查询条件未包含分区键时，PostgreSQL 会扫描所有分区...",
    "tags": ["分区表", "慢查询"],
    "matchPatterns": ["partition", "all partitions", "分区扫描"],
    "priority": 8
  }'

# 搜索知识库
curl "http://localhost:8080/api/v1/knowledge/search?keyword=慢查询&limit=5"
```

知识库分类 (`category`) 说明:
| 分类 | 用途 |
|------|------|
| `SLOW_QUERY_PATTERN` | 慢查询模式识别 |
| `ERROR_CODE` | 错误码/异常模式 |
| `BEST_PRACTICE` | 监控阈值/最佳实践 |
| `RUNBOOK` | 标准排障流程 |

### 6. 更换 LLM 模型

系统通过 OpenAI 兼容 API 接入 LLM，可替换为任何兼容的模型服务:

```env
# MiniMax (默认)
OPENAI_API_KEY=your-minimax-key
OPENAI_BASE_URL=https://api.minimax.chat/v1

# 或使用 DeepSeek
OPENAI_API_KEY=your-deepseek-key
OPENAI_BASE_URL=https://api.deepseek.com/v1

# 或使用自部署的 vLLM / Ollama
OPENAI_API_KEY=not-needed
OPENAI_BASE_URL=http://your-vllm-server:8000/v1
```

如需为 Router 和 Worker 使用不同模型，修改 `LangChain4jConfig.java` 中的 `modelName`。

### 7. 性能调优

```env
# JVM 参数 (根据服务器内存调整)
JAVA_OPTS=-Xms1g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError
```

`application-docker.yml` 中可调整的关键参数:
- 主库连接池: `hikari.maximum-pool-size` (默认 20)
- 监控库连接池: 默认 5，只读查询足够
- 监控库查询超时: `statement_timeout=10s`
- Agent 线程池: `AsyncConfig` 中 core=4, max=8

---

## API 使用示例

### 发起诊断（SSE 流式）

```bash
curl -N -X POST http://localhost:8080/api/v1/diagnosis/stream \
  -H "Content-Type: application/json" \
  -d '{"query": "数据库响应变慢，最近10分钟有大量超时"}'
```

SSE 事件流:
```
event: session
data: {"sessionId": "abc-123"}

event: trace
data: {"stepIndex":1, "agentName":"ROUTER", "stepType":"THOUGHT", "content":"分析意图..."}

event: trace
data: {"stepIndex":2, "agentName":"TEXT2SQL", "stepType":"ACTION", "content":"生成SQL查询慢查询日志"}

event: result
data: {"sessionId":"abc-123", "rootCause":"order_detail 表缺少索引", "confidence":0.92}

event: done
data: {"sessionId":"abc-123", "status":"COMPLETED"}
```

### 查询诊断结果

```bash
# 诊断结果 + 统计
curl http://localhost:8080/api/v1/diagnosis/{sessionId}

# 完整 Trace (Agent 思考过程回放)
curl http://localhost:8080/api/v1/diagnosis/{sessionId}/trace

# Timeline 格式 (前端可视化)
curl http://localhost:8080/api/v1/diagnosis/{sessionId}/timeline

# SQL 审计记录
curl http://localhost:8080/api/v1/diagnosis/{sessionId}/sql-audit

# 历史记录 (分页)
curl "http://localhost:8080/api/v1/diagnosis/history?page=0&size=20"
```

---

## 常见问题

**Q: 启动后 app 容器一直重启?**
检查 LLM API Key 是否正确配置，查看日志: `docker compose logs app`

**Q: 诊断超时?**
检查 LLM API 连通性。如果使用国内网络访问海外 API，考虑配置代理或使用国内模型服务。

**Q: 如何重置模拟数据?**
```bash
docker compose down -v    # 删除数据卷
docker compose up -d      # 重新创建，自动注入种子数据
```

**Q: 如何查看 Agent 的完整思考过程?**
调用 `/api/v1/diagnosis/{sessionId}/trace` 接口，返回每一步的 THOUGHT → ACTION → OBSERVATION → REFLECTION → DECISION 链路。

## License

MIT
