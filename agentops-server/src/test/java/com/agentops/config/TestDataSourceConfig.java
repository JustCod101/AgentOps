package com.agentops.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 测试用数据源配置 — 使用 H2 内存库替代 PostgreSQL
 *
 * 覆盖生产 DataSourceConfig 中的所有 Bean，
 * 使集成测试无需真实 PostgreSQL 即可运行。
 */
@TestConfiguration
@EnableJpaRepositories(
        basePackages = "com.agentops.repository",
        entityManagerFactoryRef = "primaryEntityManagerFactory",
        transactionManagerRef = "primaryTransactionManager"
)
public class TestDataSourceConfig {

    private final Environment env;

    public TestDataSourceConfig(Environment env) {
        this.env = env;
    }

    @Primary
    @Bean(name = "primaryDataSource")
    public DataSource primaryDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env.getProperty("spring.datasource.primary.url"));
        config.setUsername(env.getProperty("spring.datasource.primary.username"));
        config.setPassword(env.getProperty("spring.datasource.primary.password"));
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(5);
        config.setPoolName("test-primary-pool");
        return new HikariDataSource(config);
    }

    @Primary
    @Bean(name = "primaryEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean primaryEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("primaryDataSource") DataSource dataSource) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");

        return builder
                .dataSource(dataSource)
                .packages("com.agentops.domain.entity")
                .persistenceUnit("primary")
                .properties(properties)
                .build();
    }

    @Primary
    @Bean(name = "primaryTransactionManager")
    public PlatformTransactionManager primaryTransactionManager(
            @Qualifier("primaryEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean(name = "monitorDataSource")
    public DataSource monitorDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env.getProperty("spring.datasource.monitor.url"));
        config.setUsername(env.getProperty("spring.datasource.monitor.username"));
        config.setPassword(env.getProperty("spring.datasource.monitor.password"));
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(2);
        config.setPoolName("test-monitor-pool");
        config.setReadOnly(true);
        return new HikariDataSource(config);
    }
}
