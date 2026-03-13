package com.agentops;

import com.agentops.agent.LogAnalysisAgent;
import com.agentops.agent.MetricQueryAgent;
import com.agentops.agent.ReportAgent;
import com.agentops.agent.RouterAgent;
import com.agentops.agent.Text2SqlAgent;
import com.agentops.config.TestDataSourceConfig;
import com.agentops.tool.SandboxSqlExecutor;
import com.agentops.tool.SqlSanitizer;
import com.agentops.trace.TraceStore;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用上下文加载测试
 *
 * 验证 Spring IoC 容器能正确初始化所有 Bean，
 * 包括双数据源、Agent 层、Tool 层、Trace 层的依赖注入。
 *
 * 外部依赖处理:
 * - PostgreSQL → H2 内存库（TestDataSourceConfig 覆盖）
 * - Redis → MockBean
 * - LLM (OpenAI) → MockBean
 */
@SpringBootTest
@Import(TestDataSourceConfig.class)
@DisplayName("AgentOps 应用上下文测试")
class AgentOpsApplicationTests {

    @MockBean(name = "routerModel")
    private ChatLanguageModel routerModel;

    @MockBean(name = "workerModel")
    private ChatLanguageModel workerModel;

    @MockBean
    @SuppressWarnings("rawtypes")
    private RedisTemplate redisTemplate;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Spring 上下文加载成功")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("Agent 层 Bean 注入正确")
    void agentBeansLoaded() {
        assertThat(context.getBean(RouterAgent.class)).isNotNull();
        assertThat(context.getBean(Text2SqlAgent.class)).isNotNull();
        assertThat(context.getBean(LogAnalysisAgent.class)).isNotNull();
        assertThat(context.getBean(MetricQueryAgent.class)).isNotNull();
        assertThat(context.getBean(ReportAgent.class)).isNotNull();
    }

    @Test
    @DisplayName("Tool 层 Bean 注入正确")
    void toolBeansLoaded() {
        assertThat(context.getBean(SqlSanitizer.class)).isNotNull();
        assertThat(context.getBean(SandboxSqlExecutor.class)).isNotNull();
    }

    @Test
    @DisplayName("Trace 层 Bean 注入正确")
    void traceBeansLoaded() {
        assertThat(context.getBean(TraceStore.class)).isNotNull();
    }
}
