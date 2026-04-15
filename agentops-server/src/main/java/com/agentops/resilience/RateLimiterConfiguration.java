package com.agentops.resilience;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimiterConfiguration {

    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        RateLimiterConfig diagnosisConfig = RateLimiterConfig.custom()
                .limitForPeriod(10)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build();

        RateLimiterConfig knowledgeConfig = RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build();

        RateLimiterRegistry registry = RateLimiterRegistry.ofDefaults();
        registry.addConfiguration("diagnosisConfig", diagnosisConfig);
        registry.addConfiguration("knowledgeConfig", knowledgeConfig);

        return registry;
    }

    @Bean
    public RateLimiter diagnosisRateLimiter(RateLimiterRegistry registry) {
        return registry.rateLimiter("diagnosis", "diagnosisConfig");
    }

    @Bean
    public RateLimiter knowledgeRateLimiter(RateLimiterRegistry registry) {
        return registry.rateLimiter("knowledge", "knowledgeConfig");
    }
}
