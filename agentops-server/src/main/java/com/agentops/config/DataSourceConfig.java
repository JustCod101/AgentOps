package com.agentops.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableJpaRepositories(
        basePackages = "com.agentops.repository",
        entityManagerFactoryRef = "primaryEntityManagerFactory",
        transactionManagerRef = "primaryTransactionManager"
)
public class DataSourceConfig {

    private final Environment env;

    public DataSourceConfig(Environment env) {
        this.env = env;
    }

    // ==================== 主库 (agentops) ====================

    @Primary
    @Bean(name = "primaryDataSource")
    public DataSource primaryDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env.getProperty("spring.datasource.primary.url"));
        config.setUsername(env.getProperty("spring.datasource.primary.username"));
        config.setPassword(env.getProperty("spring.datasource.primary.password"));
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setPoolName("primary-pool");
        return new HikariDataSource(config);
    }

    @Primary
    @Bean(name = "primaryEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean primaryEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("primaryDataSource") DataSource dataSource) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "update");
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");

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

    // ==================== 只读监控库 (monitor) ====================

    @Bean(name = "monitorDataSource")
    public DataSource monitorDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env.getProperty("spring.datasource.monitor.url"));
        config.setUsername(env.getProperty("spring.datasource.monitor.username"));
        config.setPassword(env.getProperty("spring.datasource.monitor.password"));
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setPoolName("monitor-readonly-pool");
        config.setReadOnly(true);
        config.setConnectionTimeout(5000);
        config.setConnectionInitSql(
                "SET statement_timeout = '10s'; SET default_transaction_read_only = ON;"
        );
        return new HikariDataSource(config);
    }
}
