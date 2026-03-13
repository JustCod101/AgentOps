# AgentOps Server

基于 **SpringBoot 3.2 + LangChain4j + MiniMax-M2.5** 的多 Agent 协同智能运维排障系统。输入一句自然语言（如 "数据库响应变慢"），系统自动调度多个专家 Agent 并行排查，最终输出结构化诊断报告。

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
│                  RouterAgent (MiniMax-M2.5)                    │
│  1. LLM 意图识别 → DB_SLOW_QUERY / LOG_ERROR / METRIC_ANOMALY │
│  2. 生成 TaskPlan → 按优先级分组并行调度 Worker Agent            │
│  3. 收集结果 → 调用 ReportAgent 汇总                           │
└───────┬──────────┬──────────┬──────────┬─────────────────────┘
        │          │          │          │
        ▼          ▼          ▼          ▼
┌────────┐  ┌───────────┐  ┌──────────┐  ┌───────────┐
│Text2Sql│  │LogAnalysis│  │MetricQry │  │  Report   │
│ Agent  │  │  Agent    │  │  Agent   │  │  Agent    │
│MiniMax │  │ MiniMax   │  │ MiniMax  │  │ MiniMax   │
│ -M2.5  │  │  -M2.5    │  │  -M2.5   │  │  -M2.5    │
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
| **RouterAgent** | MiniMax-M2.5 | 意图识别、任务编排、结果汇总 | — |
| **Text2SqlAgent** | MiniMax-M2.5 | 自然语言 → SQL、沙盒执行 | SqlSanitizer + SandboxSqlExecutor |
| **LogAnalysisAgent** | MiniMax-M2.5 | 错误日志聚合分析 | LogQueryTool (预定义查询) |
| **MetricQueryAgent** | MiniMax-M2.5 | 系统指标趋势 + 异常检测 | MetricQueryTool (预定义查询) |
| **ReportAgent** | MiniMax-M2.5 | 多源证据聚合、Markdown 报告 | — |

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
- **知识库**: 运维知识库 CRUD + 模式匹配搜索，Agent 诊断时自动检索相关知识

## 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | SpringBoot 3.2, JDK 17 |
| AI | LangChain4j 0.35, MiniMax-M2.5 (OpenAI 兼容 API) |
| SQL 解析 | JSqlParser 4.9 |
| 数据库 | PostgreSQL 16 (主库 + 监控只读库) |
| 缓存 | Redis 7 |
| 构建 | Maven, Docker |

## API 接口一览

### 诊断 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/diagnosis/stream` | SSE 流式诊断 |
| GET | `/api/v1/diagnosis/{sessionId}` | 查询诊断结果 |
| GET | `/api/v1/diagnosis/{sessionId}/trace` | 完整 Trace |
| GET | `/api/v1/diagnosis/{sessionId}/timeline` | Timeline 格式 |
| GET | `/api/v1/diagnosis/{sessionId}/sql-audit` | SQL 审计记录 |
| GET | `/api/v1/diagnosis/history` | 历史记录(分页) |

### 知识库 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/knowledge` | 列表(可选 category) |
| GET | `/api/v1/knowledge/{id}` | 详情 |
| GET | `/api/v1/knowledge/search` | 搜索(keyword + category) |
| POST | `/api/v1/knowledge` | 新增 |
| PUT | `/api/v1/knowledge/{id}` | 更新 |
| DELETE | `/api/v1/knowledge/{id}` | 删除 |

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
│   │   ├── MetricQueryTool.java    # 指标预定义查询
│   │   └── KnowledgeSearchTool.java# 知识库搜索
│   ├── statemachine/           # 状态机
│   │   ├── AgentStateMachine.java  # 状态定义 & 转移规则
│   │   └── ReflectionEngine.java   # 反思引擎 (LLM 驱动)
│   ├── trace/                  # 全链路追踪
│   │   ├── TraceStore.java         # Trace 持久化 & 监听
│   │   ├── TraceListener.java      # 监听器接口
│   │   └── TraceTimelineItem.java  # Timeline DTO
│   ├── controller/             # REST API
│   │   ├── DiagnosisController.java
│   │   └── KnowledgeController.java
│   ├── config/                 # 配置
│   │   ├── DataSourceConfig.java   # 双数据源
│   │   ├── LangChain4jConfig.java  # LLM 配置 (MiniMax-M2.5)
│   │   ├── AsyncConfig.java        # 线程池
│   │   └── RedisConfig.java
│   ├── domain/entity/          # JPA 实体
│   └── repository/             # 数据访问
├── src/test/                   # 单元测试 (120+ test cases)
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
| T-15min ~ NOW | 故障期 | 慢查询堆积 → 连接池打满 → 上游服务超时 → 级联故障 |

Agent 系统能够自动发现这条因果链并生成诊断报告。

## License

MIT
