# 基于多智能体协同的智能 AIOps 排障专家 — 完整解决方案设计

## 一、项目概述

**项目名称：** AgentOps — 多智能体协同 AIOps 排障专家系统  
**技术定位：** 基于 SpringBoot + LangChain4j 构建的 Router-Worker 多智能体架构，面向数据库与微服务故障的智能诊断平台  
**核心价值：** 将"意图识别 → 数据查询 → 日志分析 → 报告生成"全链路由专职 Agent 协同完成，解决单体 LLM 在复杂排障场景下的幻觉、准确率低、不可追溯三大问题

---

## 二、项目背景与问题分析

### 2.1 运维痛点

```
传统排障流程（人工）:
┌─────────┐   ┌──────────┐   ┌────────────┐   ┌─────────────┐
│ 收到告警  │→ │ 登录监控  │→ │ 手动查 SQL  │→ │ 翻日志找线索 │→ 写报告
│ (30s)    │   │ (2min)   │   │ (5-10min)  │   │ (10-30min)  │   (10min)
└─────────┘   └──────────┘   └────────────┘   └─────────────┘
                                        平均排障时间: 30-60 分钟

单体 LLM 直接排障的问题:
❌ 幻觉: 编造不存在的表名、指标名
❌ 上下文丢失: 长对话中遗忘前面的查询结果
❌ 不可控: 生成危险 SQL (DROP/UPDATE) 无拦截
❌ 黑盒: 不知道 LLM 做了什么决策，无法审计
❌ 低准确率: 复杂故障场景下成功率 < 50%
```

### 2.2 AgentOps 解决方案

```
AgentOps 多智能体排障流程:

用户: "最近 10 分钟数据库响应变慢，帮我排查"
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  Router Agent (意图识别)                                     │
│  分析问题类型 → 分解子任务 → 派发给专职 Worker Agent          │
└────────┬──────────────────┬──────────────────┬──────────────┘
         │                  │                  │
    ┌────▼────┐       ┌────▼────┐        ┌────▼────┐
    │ Text2SQL │       │ 日志分析 │        │ 指标查询 │
    │ Agent    │       │ Agent   │        │ Agent   │
    │          │       │         │        │         │
    │ 查慢SQL  │       │ 查错误  │        │ 查CPU/  │
    │ 查连接数 │       │ 日志    │        │ 内存/QPS│
    └────┬────┘       └────┬────┘        └────┬────┘
         │                  │                  │
         │   ┌──────────────┼──────────────────┘
         │   │              │
    ┌────▼───▼──────────────▼────┐
    │  Report Agent (报告生成)    │
    │  汇总发现 → 根因分析       │
    │  → 修复建议 → 结构化报告   │
    └────────────────────────────┘
    
    排障时间: 2-5 分钟 | 成功率: 85%
```

---

## 三、整体架构设计

### 3.1 系统分层架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                       接入层 Access Layer                            │
│  REST API · WebSocket 实时推送 · SSE Agent 思考过程流式输出            │
├──────────────────────────────────────────────────────────────────────┤
│                       编排层 Orchestration Layer                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                  Router Agent (路由编排器)                     │   │
│  │  ·意图识别 & 分类  ·任务分解  ·Agent 派发  ·结果汇总           │   │
│  └──────────┬───────────────┬────────────────┬──────────────────┘   │
│             │               │                │                      │
│  ┌──────────▼──────┐ ┌─────▼───────┐ ┌──────▼──────────┐          │
│  │ Text2SQL Agent  │ │ LogAnalysis │ │ MetricQuery     │          │
│  │ (查标专家)       │ │ Agent       │ │ Agent           │          │
│  │                 │ │ (日志专家)   │ │ (指标专家)       │          │
│  │ ·动态SQL生成    │ │ ·关键字提取 │ │ ·Prometheus查询 │          │
│  │ ·AST安全拦截   │ │ ·模式匹配   │ │ ·异常检测       │          │
│  │ ·只读沙盒执行  │ │ ·异常聚类   │ │ ·趋势分析       │          │
│  └─────────────────┘ └─────────────┘ └─────────────────┘          │
│             │               │                │                      │
│  ┌──────────▼───────────────▼────────────────▼──────────────────┐   │
│  │                  Report Agent (报告专家)                       │   │
│  │  ·多源证据汇总  ·根因推断  ·修复建议  ·Markdown 报告生成        │   │
│  └──────────────────────────────────────────────────────────────┘   │
├──────────────────────────────────────────────────────────────────────┤
│                       工具层 Tool Layer                               │
│  ┌────────────┐ ┌──────────┐ ┌────────────┐ ┌───────────────────┐  │
│  │ SQL执行器   │ │ 日志查询器│ │ 指标查询器  │ │ Knowledge Base   │  │
│  │ (沙盒隔离)  │ │ (ES/文件) │ │ (Prom API) │ │ (运维知识库)      │  │
│  └────────────┘ └──────────┘ └────────────┘ └───────────────────┘  │
├──────────────────────────────────────────────────────────────────────┤
│                   可观测层 Observability Layer                        │
│  ┌────────────────┐ ┌──────────────────┐ ┌────────────────────┐    │
│  │ Trace 全链路追踪│ │ 状态机 & 反思引擎 │ │ 排障轨迹回放      │    │
│  │ (Thought-Action │ │ Act-Observe-     │ │ (可视化 Timeline) │    │
│  │  -Observation)  │ │  Reflect Loop    │ │                   │    │
│  └────────────────┘ └──────────────────┘ └────────────────────┘    │
├──────────────────────────────────────────────────────────────────────┤
│                       基础设施层 Infrastructure                       │
│  PostgreSQL (诊断记录/Trace) · Redis (会话/缓存) · Docker (沙盒)     │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2 核心技术栈

| 层级 | 技术选型 | 用途 |
|------|---------|------|
| 核心框架 | SpringBoot 3.2 + JDK 17 | 服务基座 |
| Agent 框架 | LangChain4j 0.35+ | Agent 编排、Tool 调用、Memory 管理 |
| LLM 调用 | OpenAI API / 本地 Ollama | GPT-4o(Router) + GPT-4o-mini(Worker) |
| SQL 安全 | JSqlParser + 自研 AST 拦截器 | SQL 语法分析、危险操作拦截 |
| 沙盒执行 | HikariCP 只读连接池 + Docker | SQL 隔离执行 |
| 日志查询 | Elasticsearch / 文件 API | 日志检索与分析 |
| 指标查询 | Prometheus HTTP API | 系统指标查询 |
| 状态机 | 自研 StateMachine | Agent 执行状态流转 + 反思重试 |
| 全链路追踪 | PostgreSQL + 自研 TraceStore | Thought-Action-Observation 持久化 |
| 流式输出 | SSE (Server-Sent Events) | Agent 思考过程实时推送 |
| 缓存 | Redis 7 | 会话上下文、Tool 结果缓存 |
| 容器化 | Docker + Docker Compose | 全栈部署 + SQL 沙盒 |

---

## 四、数据库建模（重点展示）

### 4.1 完整 Schema 设计

```sql
-- ============================================================
-- AgentOps 数据库 Schema
-- ============================================================

-- ============================================================
-- 1. 诊断会话管理
-- ============================================================

CREATE TABLE diagnosis_session (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL UNIQUE,       -- UUID
    user_id         VARCHAR(64),
    title           VARCHAR(256),                      -- 自动从问题生成
    initial_query   TEXT NOT NULL,                      -- 用户原始问题
    intent_type     VARCHAR(32),                        -- 意图分类
    -- DB_SLOW_QUERY / DB_CONNECTION / SERVICE_ERROR / 
    -- SERVICE_LATENCY / MEMORY_LEAK / GENERAL
    
    status          VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    -- CREATED → ROUTING → EXECUTING → REFLECTING → COMPLETED → FAILED
    
    -- 结果
    root_cause      TEXT,                               -- 根因分析
    fix_suggestion  TEXT,                               -- 修复建议
    report_markdown TEXT,                               -- 完整报告
    confidence      FLOAT,                              -- 诊断置信度 0-1
    
    -- 统计
    agent_count     INT DEFAULT 0,                      -- 参与的 Agent 数
    tool_call_count INT DEFAULT 0,                      -- Tool 调用总次数
    retry_count     INT DEFAULT 0,                      -- 反思重试次数
    total_tokens    INT DEFAULT 0,                      -- 总 Token 消耗
    total_latency_ms BIGINT DEFAULT 0,                  -- 总耗时
    
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_session_status ON diagnosis_session(status);
CREATE INDEX idx_session_intent ON diagnosis_session(intent_type);
CREATE INDEX idx_session_time ON diagnosis_session(created_at DESC);

-- ============================================================
-- 2. Agent 执行 Trace（核心可观测表）
-- ============================================================

CREATE TABLE agent_trace (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL,
    agent_name      VARCHAR(64) NOT NULL,
    -- ROUTER / TEXT2SQL / LOG_ANALYSIS / METRIC_QUERY / REPORT
    
    step_index      INT NOT NULL,                       -- 步骤序号
    step_type       VARCHAR(16) NOT NULL,
    -- THOUGHT: Agent 的思考推理过程
    -- ACTION:  调用 Tool 的动作
    -- OBSERVATION: Tool 返回的结果
    -- REFLECTION: 反思纠错
    -- DECISION: 路由决策
    
    content         TEXT NOT NULL,                       -- 步骤内容
    
    -- ACTION 类型专用字段
    tool_name       VARCHAR(64),                        -- 调用的 Tool 名
    tool_input      JSONB,                              -- Tool 输入参数
    tool_output     TEXT,                               -- Tool 输出结果
    tool_success    BOOLEAN,                            -- 执行是否成功
    
    -- 性能
    latency_ms      INT,
    token_count     INT,
    
    -- 状态机
    state_before    VARCHAR(32),                         -- 执行前状态
    state_after     VARCHAR(32),                         -- 执行后状态
    
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trace_session ON agent_trace(session_id, step_index);
CREATE INDEX idx_trace_agent ON agent_trace(agent_name, created_at DESC);
CREATE INDEX idx_trace_type ON agent_trace(step_type);

-- ============================================================
-- 3. SQL 安全审计日志
-- ============================================================

CREATE TABLE sql_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL,
    original_sql    TEXT NOT NULL,                       -- LLM 生成的原始 SQL
    sanitized_sql   TEXT,                                -- 安全检查后的 SQL
    is_allowed      BOOLEAN NOT NULL,                    -- 是否允许执行
    reject_reason   VARCHAR(256),                        -- 拒绝原因
    
    -- AST 解析结果
    sql_type        VARCHAR(16),                         -- SELECT / INSERT / UPDATE / DELETE
    tables_accessed JSONB,                               -- 访问的表名列表
    has_subquery    BOOLEAN DEFAULT FALSE,
    has_join        BOOLEAN DEFAULT FALSE,
    estimated_rows  BIGINT,                              -- EXPLAIN 预估行数
    
    -- 执行结果
    executed        BOOLEAN DEFAULT FALSE,
    execution_ms    INT,
    result_rows     INT,
    error_message   TEXT,
    
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sql_audit_session ON sql_audit_log(session_id);
CREATE INDEX idx_sql_audit_allowed ON sql_audit_log(is_allowed);

-- ============================================================
-- 4. 运维知识库（用于 RAG 增强）
-- ============================================================

CREATE TABLE knowledge_entry (
    id              BIGSERIAL PRIMARY KEY,
    category        VARCHAR(64) NOT NULL,
    -- SLOW_QUERY_PATTERN / ERROR_CODE / BEST_PRACTICE / RUNBOOK
    title           VARCHAR(256) NOT NULL,
    content         TEXT NOT NULL,
    tags            JSONB DEFAULT '[]',
    match_patterns  JSONB DEFAULT '[]',                  -- 匹配模式关键词
    priority        INT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_knowledge_category ON knowledge_entry(category);

-- ============================================================
-- 5. 目标数据库监控视图（模拟监控数据源）
-- ============================================================

-- 慢查询记录表（模拟 performance_schema）
CREATE TABLE slow_query_log (
    id              BIGSERIAL PRIMARY KEY,
    query_text      TEXT NOT NULL,
    query_hash      VARCHAR(64),
    execution_time_ms BIGINT NOT NULL,
    rows_examined   BIGINT,
    rows_sent       BIGINT,
    lock_time_ms    BIGINT DEFAULT 0,
    db_name         VARCHAR(64),
    user_host       VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_slow_query_time ON slow_query_log(created_at DESC);
CREATE INDEX idx_slow_query_duration ON slow_query_log(execution_time_ms DESC);

-- 数据库连接状态表
CREATE TABLE db_connection_status (
    id              BIGSERIAL PRIMARY KEY,
    total_connections INT NOT NULL,
    active_connections INT NOT NULL,
    idle_connections INT NOT NULL,
    waiting_connections INT NOT NULL,
    max_connections INT NOT NULL,
    sampled_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 微服务错误日志表（模拟 ELK 数据源）
CREATE TABLE service_error_log (
    id              BIGSERIAL PRIMARY KEY,
    service_name    VARCHAR(64) NOT NULL,
    log_level       VARCHAR(8) NOT NULL,                 -- ERROR / WARN / INFO
    message         TEXT NOT NULL,
    stack_trace     TEXT,
    trace_id        VARCHAR(36),
    span_id         VARCHAR(16),
    host            VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_error_log_service ON service_error_log(service_name, created_at DESC);
CREATE INDEX idx_error_log_level ON service_error_log(log_level, created_at DESC);

-- 系统指标表（模拟 Prometheus 数据）
CREATE TABLE system_metric (
    id              BIGSERIAL PRIMARY KEY,
    metric_name     VARCHAR(128) NOT NULL,               -- cpu_usage / memory_usage / qps / latency_p99
    service_name    VARCHAR(64),
    value           FLOAT NOT NULL,
    labels          JSONB,
    sampled_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_metric_name ON system_metric(metric_name, sampled_at DESC);
CREATE INDEX idx_metric_service ON system_metric(service_name, sampled_at DESC);
```

---

## 五、核心模块详细设计

### 5.1 Router Agent（路由编排器）

```java
/**
 * Router Agent — 多智能体编排核心
 * 
 * 职责:
 * 1. 意图识别: 判断问题类型（数据库慢查/连接/服务报错/内存泄漏...）
 * 2. 任务分解: 将复杂问题拆解为多个子任务
 * 3. Agent 派发: 按依赖关系调度 Worker Agent
 * 4. 结果汇总: 收集各 Agent 输出，触发 Report Agent
 */
@Service
public class RouterAgent {

    @Autowired private LangChain4jClient llmClient;
    @Autowired private Text2SqlAgent text2SqlAgent;
    @Autowired private LogAnalysisAgent logAnalysisAgent;
    @Autowired private MetricQueryAgent metricQueryAgent;
    @Autowired private ReportAgent reportAgent;
    @Autowired private TraceStore traceStore;
    @Autowired private SessionRepository sessionRepo;

    private static final String ROUTER_SYSTEM_PROMPT = """
        你是一个 AIOps 排障路由器。你的职责是分析用户的运维问题，
        判断意图类型，并将任务分解为多个子任务。
        
        可用的 Worker Agent:
        1. TEXT2SQL - 查询数据库监控数据（慢SQL、连接数、锁等待等）
        2. LOG_ANALYSIS - 分析服务错误日志（异常堆栈、错误模式等）
        3. METRIC_QUERY - 查询系统指标（CPU、内存、QPS、延迟等）
        
        请严格按照以下 JSON 格式返回任务计划:
        {
          "intent": "DB_SLOW_QUERY",
          "confidence": 0.9,
          "tasks": [
            {"agent": "TEXT2SQL", "task": "查询最近10分钟的慢SQL记录", "priority": 1},
            {"agent": "METRIC_QUERY", "task": "查询数据库QPS和延迟趋势", "priority": 1},
            {"agent": "LOG_ANALYSIS", "task": "查看相关服务是否有报错", "priority": 2}
          ],
          "reasoning": "用户描述了数据库响应变慢的问题，需要..."
        }
        
        priority 相同的任务可以并行执行，不同 priority 按顺序执行。
    """;

    public Mono<DiagnosisResult> diagnose(String userQuery, String sessionId) {
        DiagnosisSession session = createSession(sessionId, userQuery);
        
        return Mono.fromCallable(() -> {
            // ===== Step 1: 意图识别 + 任务分解 =====
            traceStore.recordThought(sessionId, "ROUTER", 
                "开始分析用户问题: " + userQuery);
            
            TaskPlan plan = identifyIntentAndPlan(userQuery, sessionId);
            
            traceStore.recordDecision(sessionId, "ROUTER",
                "意图: " + plan.getIntent() + ", 分解为 " 
                + plan.getTasks().size() + " 个子任务");
            
            session.setIntentType(plan.getIntent());
            
            // ===== Step 2: 按优先级执行子任务 =====
            Map<String, AgentResult> agentResults = new LinkedHashMap<>();
            
            // 按 priority 分组
            Map<Integer, List<TaskItem>> grouped = plan.getTasks().stream()
                .collect(Collectors.groupingBy(TaskItem::getPriority,
                    TreeMap::new, Collectors.toList()));
            
            for (var entry : grouped.entrySet()) {
                List<TaskItem> parallelTasks = entry.getValue();
                
                // 同优先级并行执行
                List<CompletableFuture<AgentResult>> futures = parallelTasks.stream()
                    .map(task -> CompletableFuture.supplyAsync(() -> 
                        dispatchToAgent(task, sessionId, userQuery)))
                    .toList();
                
                for (int i = 0; i < futures.size(); i++) {
                    AgentResult result = futures.get(i).get(120, TimeUnit.SECONDS);
                    agentResults.put(parallelTasks.get(i).getAgent(), result);
                }
            }
            
            // ===== Step 3: 报告生成 =====
            traceStore.recordThought(sessionId, "ROUTER",
                "所有 Worker 完成，触发报告生成");
            
            DiagnosisReport report = reportAgent.generateReport(
                userQuery, plan, agentResults, sessionId);
            
            // ===== Step 4: 更新会话 =====
            session.setRootCause(report.getRootCause());
            session.setFixSuggestion(report.getFixSuggestion());
            session.setReportMarkdown(report.getMarkdown());
            session.setConfidence(report.getConfidence());
            session.setStatus("COMPLETED");
            session.setCompletedAt(Instant.now());
            sessionRepo.save(session);
            
            return new DiagnosisResult(session, report, 
                traceStore.getFullTrace(sessionId));
            
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private TaskPlan identifyIntentAndPlan(String query, String sessionId) {
        String response = llmClient.chat(ROUTER_SYSTEM_PROMPT, query, "gpt-4o");
        
        traceStore.recordAction(sessionId, "ROUTER", "llm_intent_classify",
            Map.of("query", query), response, true);
        
        return parseTaskPlan(response);
    }

    private AgentResult dispatchToAgent(TaskItem task, String sessionId, 
                                         String originalQuery) {
        return switch (task.getAgent()) {
            case "TEXT2SQL" -> text2SqlAgent.execute(
                task.getTask(), sessionId, originalQuery);
            case "LOG_ANALYSIS" -> logAnalysisAgent.execute(
                task.getTask(), sessionId, originalQuery);
            case "METRIC_QUERY" -> metricQueryAgent.execute(
                task.getTask(), sessionId, originalQuery);
            default -> throw new IllegalArgumentException(
                "Unknown agent: " + task.getAgent());
        };
    }
}
```

### 5.2 Text2SQL Agent + AST 安全拦截 + 沙盒执行

```java
/**
 * Text2SQL Agent — 数据库查询专家
 * 
 * 核心能力:
 * 1. 根据自然语言生成 SQL 查询
 * 2. AST 安全拦截: 禁止 INSERT/UPDATE/DELETE/DROP
 * 3. 只读沙盒执行: 隔离连接池 + 超时控制
 * 4. Act-Observe-Reflect 自纠错循环
 */
@Service
public class Text2SqlAgent {

    @Autowired private LangChain4jClient llmClient;
    @Autowired private SqlSanitizer sqlSanitizer;
    @Autowired private SandboxSqlExecutor sandboxExecutor;
    @Autowired private TraceStore traceStore;
    @Autowired private ReflectionEngine reflectionEngine;

    private static final int MAX_RETRIES = 3;

    private static final String TEXT2SQL_SYSTEM_PROMPT = """
        你是一个数据库查询专家。根据用户的运维问题，生成安全的 SELECT 查询。
        
        可查询的表:
        - slow_query_log: 慢查询记录 (query_text, execution_time_ms, rows_examined, created_at)
        - db_connection_status: 连接状态 (total_connections, active_connections, sampled_at)
        - service_error_log: 错误日志 (service_name, log_level, message, stack_trace, created_at)
        - system_metric: 系统指标 (metric_name, service_name, value, sampled_at)
        
        规则:
        1. 只能生成 SELECT 语句
        2. 必须加 LIMIT (最大 100)
        3. 必须加时间范围条件
        4. 返回格式: {"sql": "SELECT ...", "explanation": "这个查询会..."}
    """;

    public AgentResult execute(String task, String sessionId, String context) {
        traceStore.recordThought(sessionId, "TEXT2SQL",
            "接收任务: " + task + "，准备生成 SQL 查询");

        // Act-Observe-Reflect 循环
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // ===== ACT: 生成 SQL =====
                String prompt = buildPrompt(task, context, attempt);
                String llmResponse = llmClient.chat(
                    TEXT2SQL_SYSTEM_PROMPT, prompt, "gpt-4o-mini");
                
                SqlGenResult sqlGen = parseSqlResponse(llmResponse);
                
                traceStore.recordAction(sessionId, "TEXT2SQL", "generate_sql",
                    Map.of("task", task, "attempt", attempt),
                    sqlGen.getSql(), true);

                // ===== OBSERVE: 安全检查 =====
                SqlSanitizeResult sanitized = sqlSanitizer.sanitize(sqlGen.getSql());
                
                if (!sanitized.isAllowed()) {
                    traceStore.recordObservation(sessionId, "TEXT2SQL",
                        "SQL 被安全拦截: " + sanitized.getRejectReason());
                    
                    // 反思: 为什么被拦截，调整生成
                    reflectionEngine.reflect(sessionId, "TEXT2SQL",
                        "SQL被拦截: " + sanitized.getRejectReason(),
                        "需要修改SQL以通过安全检查");
                    continue;
                }
                
                // ===== OBSERVE: 沙盒执行 =====
                SqlExecutionResult execResult = sandboxExecutor.execute(
                    sanitized.getSanitizedSql(), Duration.ofSeconds(10));
                
                traceStore.recordObservation(sessionId, "TEXT2SQL",
                    "执行结果: " + execResult.getRowCount() + " 行, " 
                    + execResult.getLatencyMs() + "ms");
                
                if (execResult.isEmpty() && attempt < MAX_RETRIES - 1) {
                    // ===== REFLECT: 空结果，调整参数重试 =====
                    String reflection = reflectionEngine.reflect(
                        sessionId, "TEXT2SQL",
                        "查询返回空结果",
                        "可能时间范围太窄或条件太严格，尝试放宽");
                    
                    traceStore.recordReflection(sessionId, "TEXT2SQL",
                        "第 " + (attempt+1) + " 次重试: " + reflection);
                    continue;
                }
                
                if (execResult.hasError() && attempt < MAX_RETRIES - 1) {
                    String reflection = reflectionEngine.reflect(
                        sessionId, "TEXT2SQL",
                        "SQL执行报错: " + execResult.getError(),
                        "需要修正SQL语法或表名");
                    
                    traceStore.recordReflection(sessionId, "TEXT2SQL",
                        "SQL 执行出错，反思: " + reflection);
                    continue;
                }
                
                // 成功
                return AgentResult.success("TEXT2SQL", 
                    formatResults(execResult), sqlGen.getExplanation());
                
            } catch (Exception e) {
                traceStore.recordObservation(sessionId, "TEXT2SQL",
                    "异常: " + e.getMessage());
                if (attempt == MAX_RETRIES - 1) {
                    return AgentResult.failure("TEXT2SQL", e.getMessage());
                }
            }
        }
        
        return AgentResult.failure("TEXT2SQL", "达到最大重试次数");
    }

    /**
     * 构建 Prompt（包含之前的反思历史）
     */
    private String buildPrompt(String task, String context, int attempt) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题: ").append(context).append("\n");
        sb.append("当前任务: ").append(task).append("\n");
        
        if (attempt > 0) {
            sb.append("\n⚠️ 这是第 ").append(attempt + 1).append(" 次尝试。");
            sb.append("之前的尝试失败了，请根据以下反馈调整:\n");
            sb.append(reflectionEngine.getLatestReflection());
            sb.append("\n请放宽时间范围或调整查询条件。");
        }
        
        return sb.toString();
    }
}
```

### 5.3 SQL AST 安全拦截器

```java
/**
 * SQL AST 安全拦截器
 * 
 * 基于 JSqlParser 解析 SQL 语法树:
 * 1. 白名单: 只允许 SELECT
 * 2. 黑名单: 禁止 DROP/TRUNCATE/ALTER/INSERT/UPDATE/DELETE
 * 3. 表名白名单: 只能查询监控相关的表
 * 4. 防护: 禁止子查询中的写操作、禁止存储过程调用
 * 5. 限制: 必须有 LIMIT、LIMIT 不超过 100
 */
@Service
public class SqlSanitizer {

    // 允许查询的表白名单
    private static final Set<String> ALLOWED_TABLES = Set.of(
        "slow_query_log",
        "db_connection_status",
        "service_error_log",
        "system_metric"
    );

    // 禁止的 SQL 类型
    private static final Set<String> FORBIDDEN_TYPES = Set.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE", 
        "ALTER", "CREATE", "GRANT", "REVOKE", "CALL"
    );

    public SqlSanitizeResult sanitize(String rawSql) {
        try {
            // 1. 基础清洗
            String sql = rawSql.trim().replaceAll(";+$", "");
            
            // 2. 多语句检测
            Statements stmts = CCJSqlParserUtil.parseStatements(sql);
            if (stmts.getStatements().size() > 1) {
                return SqlSanitizeResult.rejected(
                    "禁止多语句执行（检测到分号分隔的多条 SQL）");
            }
            
            Statement stmt = stmts.getStatements().get(0);
            
            // 3. 语句类型检查
            if (!(stmt instanceof Select)) {
                String type = stmt.getClass().getSimpleName().toUpperCase();
                return SqlSanitizeResult.rejected(
                    "仅允许 SELECT 查询，当前类型: " + type);
            }
            
            Select select = (Select) stmt;
            
            // 4. 表名白名单检查
            TablesNamesFinder tablesFinder = new TablesNamesFinder();
            List<String> tables = tablesFinder.getTableList(select);
            
            for (String table : tables) {
                String tableName = table.toLowerCase().replaceAll("`|\"", "");
                if (!ALLOWED_TABLES.contains(tableName)) {
                    return SqlSanitizeResult.rejected(
                        "禁止访问表: " + tableName 
                        + "，允许的表: " + ALLOWED_TABLES);
                }
            }
            
            // 5. 子查询安全检查（递归检查）
            SubqueryValidator subValidator = new SubqueryValidator();
            select.accept(subValidator);
            if (subValidator.hasUnsafeSubquery()) {
                return SqlSanitizeResult.rejected(
                    "子查询中包含不安全操作");
            }
            
            // 6. LIMIT 检查
            PlainSelect plainSelect = select.getPlainSelect();
            if (plainSelect != null) {
                Limit limit = plainSelect.getLimit();
                if (limit == null) {
                    // 自动追加 LIMIT 100
                    plainSelect.setLimit(new Limit()
                        .withRowCount(new LongValue(100)));
                } else {
                    // 确保 LIMIT <= 100
                    long limitValue = ((LongValue) limit.getRowCount()).getValue();
                    if (limitValue > 100) {
                        plainSelect.setLimit(new Limit()
                            .withRowCount(new LongValue(100)));
                    }
                }
            }
            
            // 7. 函数黑名单检查
            FunctionValidator funcValidator = new FunctionValidator();
            select.accept(funcValidator);
            if (funcValidator.hasUnsafeFunction()) {
                return SqlSanitizeResult.rejected(
                    "包含禁止的函数: " + funcValidator.getUnsafeFunctions());
            }
            
            String sanitizedSql = select.toString();
            
            return SqlSanitizeResult.allowed(sanitizedSql, tables);
            
        } catch (JSQLParserException e) {
            return SqlSanitizeResult.rejected(
                "SQL 语法解析失败: " + e.getMessage());
        }
    }
    
    /**
     * 子查询安全检查器
     */
    private static class SubqueryValidator extends StatementDeParser {
        private boolean unsafe = false;
        
        public boolean hasUnsafeSubquery() { return unsafe; }
        
        // 检查子查询中是否有写操作...
    }
    
    /**
     * 函数黑名单检查器
     */
    private static class FunctionValidator extends ExpressionDeParser {
        private static final Set<String> UNSAFE_FUNCTIONS = Set.of(
            "SLEEP", "BENCHMARK", "LOAD_FILE", "INTO OUTFILE",
            "INTO DUMPFILE", "SYSTEM", "EXEC", "XP_CMDSHELL"
        );
        
        private boolean unsafe = false;
        private List<String> unsafeFuncs = new ArrayList<>();
        
        public boolean hasUnsafeFunction() { return unsafe; }
        public List<String> getUnsafeFunctions() { return unsafeFuncs; }
    }
}
```

### 5.4 沙盒 SQL 执行器

```java
/**
 * 沙盒 SQL 执行器
 * 
 * 安全隔离措施:
 * 1. 独立只读连接池（与业务连接池完全隔离）
 * 2. 连接级别 SET TRANSACTION READ ONLY
 * 3. 语句超时控制 (statement_timeout = 10s)
 * 4. 结果集大小限制
 * 5. 所有执行记录审计
 */
@Service
public class SandboxSqlExecutor {

    private final DataSource readOnlyDataSource;
    @Autowired private SqlAuditRepository auditRepo;

    public SandboxSqlExecutor() {
        // 独立的只读连接池
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/monitor_db");
        config.setUsername("readonly_user");     // 只读用户
        config.setPassword("${READONLY_PASSWORD}");
        config.setMaximumPoolSize(5);            // 严格限制连接数
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setReadOnly(true);                // 连接级只读
        
        // 连接初始化: 设置超时和只读事务
        config.setConnectionInitSql(
            "SET statement_timeout = '10s'; " +
            "SET default_transaction_read_only = ON;"
        );
        
        this.readOnlyDataSource = new HikariDataSource(config);
    }

    public SqlExecutionResult execute(String sql, Duration timeout) {
        long start = System.nanoTime();
        
        try (Connection conn = readOnlyDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setQueryTimeout((int) timeout.toSeconds());
            stmt.setMaxRows(100);               // 最大返回行数
            stmt.setFetchSize(50);              // 分批获取
            
            boolean hasResult = stmt.execute();
            
            if (hasResult) {
                ResultSet rs = stmt.getResultSet();
                List<Map<String, Object>> rows = resultSetToList(rs);
                long latency = (System.nanoTime() - start) / 1_000_000;
                
                // 审计记录
                auditRepo.save(SqlAuditLog.builder()
                    .sql(sql)
                    .executed(true)
                    .executionMs((int) latency)
                    .resultRows(rows.size())
                    .build());
                
                return SqlExecutionResult.success(rows, latency);
            }
            
            return SqlExecutionResult.empty();
            
        } catch (SQLException e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            
            auditRepo.save(SqlAuditLog.builder()
                .sql(sql)
                .executed(true)
                .executionMs((int) latency)
                .errorMessage(e.getMessage())
                .build());
            
            return SqlExecutionResult.error(e.getMessage());
        }
    }
    
    private List<Map<String, Object>> resultSetToList(ResultSet rs) 
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        
        while (rs.next() && rows.size() < 100) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}
```

### 5.5 反思引擎（Act-Observe-Reflect 状态机）

```java
/**
 * 反思引擎 — 基于状态机的自纠错机制
 * 
 * 状态流转:
 * INIT → ACTING → OBSERVING → [成功] → DONE
 *                     ↓
 *                [失败/空结果]
 *                     ↓
 *               REFLECTING → ACTING (重试, 最多3次)
 *                     ↓
 *              [超过重试次数]
 *                     ↓
 *                   FAILED
 */
@Service
public class ReflectionEngine {

    @Autowired private LangChain4jClient llmClient;
    @Autowired private TraceStore traceStore;

    // Agent 状态机
    public enum AgentState {
        INIT, ACTING, OBSERVING, REFLECTING, DONE, FAILED
    }

    private static final String REFLECTION_PROMPT = """
        你是一个经验丰富的 AIOps 专家。当前一次操作结果不理想，
        请分析原因并给出调整建议。
        
        ## 当前情况
        Agent: {agent_name}
        操作: {action}
        结果: {observation}
        问题: {problem}
        
        ## 历史尝试
        {history}
        
        请分析失败原因，并给出具体的调整策略。
        格式: {"analysis": "...", "adjustment": "...", "new_params": {...}}
    """;

    // 每个 session + agent 维护独立状态
    private final ConcurrentMap<String, StateMachineContext> contexts = 
        new ConcurrentHashMap<>();

    public String reflect(String sessionId, String agentName,
                          String observation, String problem) {
        String key = sessionId + ":" + agentName;
        StateMachineContext ctx = contexts.computeIfAbsent(key, 
            k -> new StateMachineContext());
        
        // 状态转移: OBSERVING → REFLECTING
        ctx.transitionTo(AgentState.REFLECTING);
        ctx.incrementRetryCount();
        
        // 构建反思 Prompt（含历史尝试）
        String prompt = REFLECTION_PROMPT
            .replace("{agent_name}", agentName)
            .replace("{action}", ctx.getLastAction())
            .replace("{observation}", observation)
            .replace("{problem}", problem)
            .replace("{history}", ctx.getHistorySummary());
        
        String reflection = llmClient.chat(
            "你是 AIOps 反思专家", prompt, "gpt-4o-mini");
        
        ctx.addReflection(reflection);
        
        traceStore.recordReflection(sessionId, agentName, reflection);
        
        // 状态转移: REFLECTING → ACTING (准备重试)
        ctx.transitionTo(AgentState.ACTING);
        
        return reflection;
    }

    public String getLatestReflection() {
        // 返回最近一次反思结果，供下次生成时参考
        // ...
    }

    /**
     * 状态机上下文
     */
    @Data
    private static class StateMachineContext {
        private AgentState currentState = AgentState.INIT;
        private int retryCount = 0;
        private String lastAction;
        private List<String> reflectionHistory = new ArrayList<>();
        
        public void transitionTo(AgentState newState) {
            // 状态合法性校验
            validateTransition(currentState, newState);
            this.currentState = newState;
        }
        
        private void validateTransition(AgentState from, AgentState to) {
            Set<AgentState> allowed = switch (from) {
                case INIT -> Set.of(AgentState.ACTING);
                case ACTING -> Set.of(AgentState.OBSERVING);
                case OBSERVING -> Set.of(AgentState.REFLECTING, AgentState.DONE);
                case REFLECTING -> Set.of(AgentState.ACTING, AgentState.FAILED);
                default -> Set.of();
            };
            if (!allowed.contains(to)) {
                throw new IllegalStateException(
                    "Invalid transition: " + from + " → " + to);
            }
        }
        
        public String getHistorySummary() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < reflectionHistory.size(); i++) {
                sb.append("尝试 ").append(i + 1).append(": ")
                  .append(reflectionHistory.get(i)).append("\n");
            }
            return sb.toString();
        }
    }
}
```

### 5.6 全链路追踪与轨迹回放

```java
/**
 * TraceStore — Agent 全链路追踪持久化
 * 
 * 记录 Agent 的每一步:
 * - THOUGHT: 思考推理过程
 * - ACTION: 调用 Tool
 * - OBSERVATION: 观察结果
 * - REFLECTION: 反思纠错
 * - DECISION: 路由决策
 */
@Service
public class TraceStore {

    @Autowired private AgentTraceRepository traceRepo;
    private final AtomicInteger stepCounter = new AtomicInteger(0);

    public void recordThought(String sessionId, String agent, String thought) {
        save(sessionId, agent, "THOUGHT", thought, null, null, null, null);
    }

    public void recordAction(String sessionId, String agent, String toolName,
                              Map<String, Object> input, String output, 
                              boolean success) {
        AgentTrace trace = new AgentTrace();
        trace.setSessionId(sessionId);
        trace.setAgentName(agent);
        trace.setStepIndex(stepCounter.incrementAndGet());
        trace.setStepType("ACTION");
        trace.setContent("调用工具: " + toolName);
        trace.setToolName(toolName);
        trace.setToolInput(input);
        trace.setToolOutput(output);
        trace.setToolSuccess(success);
        trace.setCreatedAt(Instant.now());
        traceRepo.save(trace);
    }

    public void recordObservation(String sessionId, String agent, String obs) {
        save(sessionId, agent, "OBSERVATION", obs, null, null, null, null);
    }

    public void recordReflection(String sessionId, String agent, String ref) {
        save(sessionId, agent, "REFLECTION", ref, null, null, null, null);
    }

    public void recordDecision(String sessionId, String agent, String decision) {
        save(sessionId, agent, "DECISION", decision, null, null, null, null);
    }

    /**
     * 获取完整排障轨迹（用于回放）
     */
    public List<AgentTrace> getFullTrace(String sessionId) {
        return traceRepo.findBySessionIdOrderByStepIndex(sessionId);
    }

    /**
     * 获取排障轨迹的 Timeline 格式（用于前端可视化）
     */
    public List<TraceTimelineItem> getTimeline(String sessionId) {
        List<AgentTrace> traces = getFullTrace(sessionId);
        return traces.stream().map(t -> TraceTimelineItem.builder()
            .stepIndex(t.getStepIndex())
            .agentName(t.getAgentName())
            .stepType(t.getStepType())
            .content(t.getContent())
            .toolName(t.getToolName())
            .success(t.getToolSuccess())
            .latencyMs(t.getLatencyMs())
            .timestamp(t.getCreatedAt())
            .build()
        ).toList();
    }
    
    private void save(String sessionId, String agent, String type, 
                       String content, String toolName, 
                       Map<String,Object> input, String output, Boolean success) {
        AgentTrace trace = new AgentTrace();
        trace.setSessionId(sessionId);
        trace.setAgentName(agent);
        trace.setStepIndex(stepCounter.incrementAndGet());
        trace.setStepType(type);
        trace.setContent(content);
        trace.setToolName(toolName);
        trace.setToolInput(input);
        trace.setToolOutput(output);
        trace.setToolSuccess(success);
        trace.setCreatedAt(Instant.now());
        traceRepo.save(trace);
    }
}
```

### 5.7 REST API + SSE 实时推送

```java
@RestController
@RequestMapping("/api/v1/diagnosis")
public class DiagnosisController {

    @Autowired private RouterAgent routerAgent;
    @Autowired private TraceStore traceStore;
    @Autowired private SessionRepository sessionRepo;

    /**
     * 发起诊断（SSE 流式推送 Agent 思考过程）
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDiagnosis(@RequestParam String query) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时
        String sessionId = UUID.randomUUID().toString();

        CompletableFuture.runAsync(() -> {
            try {
                // 注册 Trace 监听器 → 每一步实时推送
                traceStore.addListener(sessionId, trace -> {
                    try {
                        emitter.send(SseEmitter.event()
                            .name(trace.getStepType().toLowerCase())
                            .data(Map.of(
                                "agent", trace.getAgentName(),
                                "type", trace.getStepType(),
                                "content", trace.getContent(),
                                "step", trace.getStepIndex(),
                                "timestamp", trace.getCreatedAt()
                            )));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });

                // 执行诊断
                DiagnosisResult result = routerAgent.diagnose(query, sessionId)
                    .block(Duration.ofMinutes(5));

                // 发送最终报告
                emitter.send(SseEmitter.event()
                    .name("report")
                    .data(result.getReport()));

                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 查询诊断结果
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<DiagnosisSession> getSession(
            @PathVariable String sessionId) {
        return sessionRepo.findBySessionId(sessionId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 排障轨迹回放（可视化 Timeline）
     */
    @GetMapping("/{sessionId}/trace")
    public List<TraceTimelineItem> getTrace(@PathVariable String sessionId) {
        return traceStore.getTimeline(sessionId);
    }

    /**
     * SQL 审计日志查询
     */
    @GetMapping("/{sessionId}/sql-audit")
    public List<SqlAuditLog> getSqlAudit(@PathVariable String sessionId) {
        return sqlAuditRepo.findBySessionId(sessionId);
    }

    /**
     * 诊断历史列表
     */
    @GetMapping("/history")
    public Page<DiagnosisSession> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return sessionRepo.findAllByOrderByCreatedAtDesc(
            PageRequest.of(page, size));
    }
}
```

---

## 六、API 接口设计

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/diagnosis/stream?query=... | SSE 流式诊断（实时推送 Agent 思考过程） |
| POST | /api/v1/diagnosis | 发起诊断（异步，返回 sessionId） |
| GET | /api/v1/diagnosis/{sessionId} | 查询诊断结果 |
| GET | /api/v1/diagnosis/{sessionId}/trace | 排障轨迹回放（Timeline） |
| GET | /api/v1/diagnosis/{sessionId}/sql-audit | SQL 审计日志 |
| GET | /api/v1/diagnosis/history | 诊断历史列表 |
| GET | /api/v1/knowledge | 知识库条目列表 |
| POST | /api/v1/knowledge | 添加知识库条目 |
| GET | /actuator/health | 健康检查 |
| GET | /actuator/prometheus | 监控指标 |

---

## 七、项目目录结构

```
agentops/
├── docker-compose.yml
├── docs/
│   ├── architecture.md
│   └── agent-design.md
├── sql/
│   ├── schema.sql                        # 主库建表
│   ├── monitor-schema.sql                # 模拟监控数据源
│   └── seed-data.sql                     # 模拟测试数据
│
├── agentops-server/
│   ├── pom.xml
│   └── src/main/java/com/agentops/
│       ├── AgentOpsApplication.java
│       │
│       ├── config/
│       │   ├── LangChain4jConfig.java      # LLM 客户端配置
│       │   ├── DataSourceConfig.java       # 双数据源(主库+只读沙盒)
│       │   ├── RedisConfig.java
│       │   └── AsyncConfig.java            # 异步线程池
│       │
│       ├── agent/                          # 多智能体核心
│       │   ├── RouterAgent.java            # 路由编排器
│       │   ├── Text2SqlAgent.java          # SQL 查询专家
│       │   ├── LogAnalysisAgent.java       # 日志分析专家
│       │   ├── MetricQueryAgent.java       # 指标查询专家
│       │   ├── ReportAgent.java            # 报告生成专家
│       │   └── model/
│       │       ├── AgentResult.java
│       │       ├── TaskPlan.java
│       │       └── DiagnosisResult.java
│       │
│       ├── tool/                           # Agent 工具层
│       │   ├── SqlSanitizer.java           # AST SQL 安全拦截器
│       │   ├── SandboxSqlExecutor.java     # 沙盒执行器
│       │   ├── LogQueryTool.java           # 日志查询工具
│       │   ├── MetricQueryTool.java        # 指标查询工具
│       │   └── KnowledgeSearchTool.java    # 知识库搜索工具
│       │
│       ├── statemachine/                   # 状态机 & 反思引擎
│       │   ├── ReflectionEngine.java
│       │   ├── AgentStateMachine.java
│       │   └── StateMachineContext.java
│       │
│       ├── trace/                          # 全链路追踪
│       │   ├── TraceStore.java
│       │   ├── TraceListener.java
│       │   └── TraceTimelineItem.java
│       │
│       ├── controller/
│       │   ├── DiagnosisController.java
│       │   ├── KnowledgeController.java
│       │   └── HealthController.java
│       │
│       ├── domain/
│       │   ├── entity/
│       │   │   ├── DiagnosisSession.java
│       │   │   ├── AgentTrace.java
│       │   │   ├── SqlAuditLog.java
│       │   │   └── KnowledgeEntry.java
│       │   ├── dto/
│       │   └── enums/
│       │
│       ├── repository/
│       │   ├── SessionRepository.java
│       │   ├── AgentTraceRepository.java
│       │   ├── SqlAuditRepository.java
│       │   └── KnowledgeRepository.java
│       │
│       └── common/
│           ├── Result.java
│           ├── GlobalExceptionHandler.java
│           └── LangChain4jClient.java      # LLM 统一调用封装
│
└── tests/
    ├── test_text2sql_agent.java
    ├── test_sql_sanitizer.java
    └── test_reflection_engine.java
```

---

## 八、Docker Compose 部署

```yaml
version: '3.8'
services:
  # 主数据库（存储诊断记录、Trace）
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: agentops
      POSTGRES_USER: admin
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    ports:
      - "5432:5432"
    volumes:
      - ./sql/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql
      - ./sql/seed-data.sql:/docker-entrypoint-initdb.d/02-seed.sql
      - pg_data:/var/lib/postgresql/data

  # 监控数据源（模拟生产环境，Agent 查询目标）
  monitor-db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: monitor
      POSTGRES_USER: readonly_user
      POSTGRES_PASSWORD: ${READONLY_PASSWORD}
    ports:
      - "5433:5432"
    volumes:
      - ./sql/monitor-schema.sql:/docker-entrypoint-initdb.d/01-monitor.sql
      - monitor_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD}
    ports:
      - "6379:6379"

  app:
    build: ./agentops-server
    depends_on:
      - postgres
      - monitor-db
      - redis
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DB_URL: jdbc:postgresql://postgres:5432/agentops
      MONITOR_DB_URL: jdbc:postgresql://monitor-db:5432/monitor
      REDIS_HOST: redis
      OPENAI_API_KEY: ${OPENAI_API_KEY}
    ports:
      - "8080:8080"

volumes:
  pg_data:
  monitor_data:
```

