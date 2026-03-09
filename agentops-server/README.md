# AgentOps — 多智能体协同 AIOps 排障专家系统

基于 **SpringBoot 3.2 + LangChain4j + GPT-4o** 的多 Agent 协同智能运维排障系统。输入一句自然语言（如 "数据库响应变慢"），系统自动调度多个专家 Agent 并行排查，最终输出结构化诊断报告。

## 系统架构

```
┌──────────────────────────────────────────────────────────────┐
│                      用户 / 前端                              │
│                  "数据库响应变慢，请排查"                       │
└──────────────────────┬───────────────────────────────────────┘
                       │ POST /api/v1/diagnosis/stream (SSE)
                       ▼
┌──────────────────────────────────────────────────────────────┐
│                   DiagnosisController                         │
│              SSE 实时推送每一步 Agent Trace                     │
└──────────────────────┬───────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────────────────┐
│                    RouterAgent (GPT-4o)                        │
│  1. LLM 意图识别 → DB_SLOW_QUERY / LOG_ERROR / METRIC_ANOMALY │
│  2. 生成 TaskPlan → 按优先级分组并行调度 Worker Agent            │
│  3. 收集结果 → 调用 ReportAgent 汇总                           │
└───────┬──────────┬──────────┬──────────┬─────────────────────┘
        │          │          │          │
        ▼          ▼          ▼          ▼
┌────────┐  ┌───────────┐  ┌──────────┐  ┌───────────┐
│Text2Sql│  │LogAnalysis│  │MetricQry │  │  Report   │
│ Agent  │  │  Agent    │  │  Agent   │  │  Agent    │
│GPT-4o  │  │GPT-4o-mini│  │GPT-4o-mi│  │  GPT-4o   │
│-mini   │  │           │  │ni       │  │           │
└───┬────┘  └─────┬─────┘  └────┬─────┘  └─────┬─────┘
    │             │             │               │
    ▼             ▼             ▼               ▼
┌─────────────────────────────────────────────────────────┐
│              Act — Observe — Reflect 循环                │
│  ┌─────┐    ┌─────────┐    ┌──────────┐                  │
│  │ ACT │───▶│OBSERVE  │───▶│REFLECT   │──retry?──▶ ACT  │
│  │执行  │    │观察结果  │    │反思纠错   │              │   │
│  └─────┘    └─────────┘    └──────────┘    max 3次 ──▶DONE│
└─────────────────────────────────────────────────────────┘
```

## Agent 设计

| Agent | 模型 | 能力 | 工具 |
|-------|------|------|------|
| **RouterAgent** | GPT-4o | 意图识别、任务编排、结果汇总 | — |
| **Text2SqlAgent** | GPT-4o-mini | 自然语言→SQL、沙盒执行 | SqlSanitizer + SandboxSqlExecutor |
| **LogAnalysisAgent** | GPT-4o-mini | 错误日志聚合分析 | LogQueryTool (预定义查询) |
| **MetricQueryAgent** | GPT-4o-mini | 系统指标趋势+异常检测 | MetricQueryTool (预定义查询) |
| **ReportAgent** | GPT-4o | 多源证据聚合、Markdown 报告 | — |

### Act-Observe-Reflect 状态机

```
INIT ──▶ ACTING ──▶ OBSERVING ──▶ REFLECTING ──▶ ACTING (重试, ≤3次)
                         │                           │
                         ▼                           ▼
                       DONE                        FAILED
```

每个 Agent 在每个 Session 中维护独立的状态机上下文（`ReflectionEngine`），支持：
- 自动重试（最多 3 次）
- 结构化反思（失败原因 + 修正建议 + 是否重试）
- 历史尝试记录传入下一轮 Prompt

## 核心特性

- **SSE 实时推送**: 每一步 Agent 思考过程实时推送到前端
- **全链路 Trace**: 5 种类型（THOUGHT / ACTION / OBSERVATION / REFLECTION / DECISION）
- **SQL 安全沙盒**: JSqlParser AST 解析 → 白名单校验 → 只读连接 + 超时执行
- **并行调度**: CompletableFuture 按优先级分组并行执行
- **自纠错机制**: LLM 反思引擎驱动重试，而非盲目重试

## 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | SpringBoot 3.2, JDK 17 |
| AI | LangChain4j 0.35, OpenAI GPT-4o / GPT-4o-mini |
| SQL 解析 | JSqlParser 4.9 |
| 数据库 | PostgreSQL 16 (主库 + 监控只读库) |
| 缓存 | Redis 7 |
| 构建 | Maven, Docker |

## 快速开始

### 前置条件

- Docker & Docker Compose
- OpenAI API Key（或兼容 API）

### 1. 克隆项目

```bash
git clone <repo-url>
cd agentops-server
```

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 填入你的 API Key
```

`.env` 文件内容:
```env
OPENAI_API_KEY=sk-your-api-key
OPENAI_BASE_URL=https://api.openai.com/v1
DB_PASSWORD=agentops123
READONLY_PASSWORD=readonly123
```

### 3. 启动服务

```bash
docker compose up -d
```

启动后：
- AgentOps Server: http://localhost:8080
- PostgreSQL 主库: localhost:5432
- PostgreSQL 监控库: localhost:5433
- Redis: localhost:6379

### 4. 验证服务

```bash
curl http://localhost:8080/actuator/health
```

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
data: {"stepIndex":2, "agentName":"TEXT2SQL", "stepType":"ACTION", "content":"调用工具: LLM_GENERATE_SQL"}

event: result
data: {"sessionId":"abc-123", "rootCause":"order_detail 表缺少索引", "confidence":0.92}

event: done
data: {"sessionId":"abc-123", "status":"COMPLETED"}
```

### 查询诊断结果

```bash
curl http://localhost:8080/api/v1/diagnosis/{sessionId}
```

### 轨迹回放

```bash
# 完整 Trace（含 toolInput/toolOutput）
curl http://localhost:8080/api/v1/diagnosis/{sessionId}/trace

# Timeline 格式（前端可视化用）
curl http://localhost:8080/api/v1/diagnosis/{sessionId}/timeline
```

### SQL 审计记录

```bash
curl http://localhost:8080/api/v1/diagnosis/{sessionId}/sql-audit
```

### 历史记录

```bash
curl http://localhost:8080/api/v1/diagnosis/history?page=0&size=20
```

## 轨迹回放示例

一次 "数据库响应变慢" 诊断的完整 Trace:

```
Step  Agent         Type         Content
────  ────────────  ──────────   ─────────────────────────────────────
  1   ROUTER        THOUGHT      收到问题: 数据库响应变慢。意图: DB_SLOW_QUERY
  2   ROUTER        DECISION     分派 3 个 Agent: Text2Sql, LogAnalysis, MetricQuery
  3   TEXT2SQL      THOUGHT      计划: 查询最近10分钟慢SQL记录
  4   TEXT2SQL      ACTION       调用工具: LLM_GENERATE_SQL → SELECT ... FROM slow_query_log
  5   TEXT2SQL      ACTION       调用工具: SQL_SANITIZE → ✅ 通过
  6   TEXT2SQL      ACTION       调用工具: SQL_EXECUTE → 18 rows, 45ms
  7   TEXT2SQL      OBSERVATION  查询成功: 18条慢查询，最慢30s
  8   LOG_ANALYSIS  THOUGHT      计划: 分析 order-service 错误日志
  9   LOG_ANALYSIS  ACTION       调用工具: LOG_QUERY_TOP_ERRORS → 5种错误模式
 10   LOG_ANALYSIS  OBSERVATION  HikariPool连接超时占68%，从T-25min开始
 11   METRIC_QUERY  THOUGHT      计划: 查询 CPU/内存/QPS/P99 趋势
 12   METRIC_QUERY  ACTION       调用工具: METRIC_TREND → 6项指标趋势
 13   METRIC_QUERY  ACTION       调用工具: ANOMALY_DETECT → 4个异常点
 14   METRIC_QUERY  OBSERVATION  CPU从15%飙到85%，P99从80ms到25s
 15   REPORT        THOUGHT      汇总 3 个 Agent 结果，生成诊断报告
 16   REPORT        ACTION       调用工具: LLM_GENERATE_REPORT
 17   ROUTER        DECISION     诊断完成，置信度: 0.92，根因: 缺失索引
```

## 项目结构

```
agentops-server/
├── src/main/java/com/agentops/
│   ├── agent/                  # Agent 实现
│   │   ├── RouterAgent.java        # 路由调度 Agent
│   │   ├── Text2SqlAgent.java      # Text2SQL Agent
│   │   ├── LogAnalysisAgent.java   # 日志分析 Agent
│   │   ├── MetricQueryAgent.java   # 指标查询 Agent
│   │   └── ReportAgent.java        # 报告生成 Agent
│   ├── tool/                   # Agent 工具
│   │   ├── SqlSanitizer.java       # SQL AST 安全校验
│   │   ├── SandboxSqlExecutor.java # SQL 沙盒执行器
│   │   ├── LogQueryTool.java       # 日志预定义查询
│   │   └── MetricQueryTool.java    # 指标预定义查询
│   ├── statemachine/           # 状态机
│   │   ├── AgentStateMachine.java  # 状态定义 & 转移规则
│   │   └── ReflectionEngine.java   # 反思引擎 (LLM 驱动)
│   ├── trace/                  # 全链路追踪
│   │   ├── TraceStore.java         # Trace 持久化 & 监听
│   │   ├── TraceListener.java      # 监听器接口
│   │   └── TraceTimelineItem.java  # Timeline DTO
│   ├── controller/             # REST API
│   │   └── DiagnosisController.java
│   ├── config/                 # 配置
│   │   ├── DataSourceConfig.java   # 双数据源
│   │   ├── LangChain4jConfig.java  # LLM 配置
│   │   ├── AsyncConfig.java        # 线程池
│   │   └── RedisConfig.java
│   ├── domain/entity/          # JPA 实体
│   └── repository/             # 数据访问
├── src/test/                   # 单元测试 (100+ test cases)
├── Dockerfile                  # 多阶段构建
├── docker-compose.yml          # 完整环境编排
└── pom.xml
```

## 监控数据说明

`seed-data.sql` 预置了一个完整的 "数据库响应变慢" 故障场景:

| 时间段 | 阶段 | 特征 |
|--------|------|------|
| T-60min ~ T-25min | 正常期 | 零星慢查询 (150~520ms)，连接数正常 |
| T-25min ~ T-15min | 恶化期 | `order_detail` 缺索引 SQL 出现，全表扫描 450万行 |
| T-15min ~ NOW | 故障期 | 慢查询堆积→连接池打满→上游服务超时→级联故障 |

Agent 系统能够自动发现这条因果链并生成诊断报告。

## License

MIT
