package com.agentops.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class LangChain4jConfig {

    @Value("${langchain4j.open-ai.api-key:}")
    private String apiKey;

    @Value("${langchain4j.open-ai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Primary
    @Bean(name = "routerModel")
    public ChatLanguageModel routerModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName("gpt-4o")
                .temperature(0.1)
                .maxTokens(2048)
                .build();
    }

    @Bean(name = "workerModel")
    public ChatLanguageModel workerModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName("gpt-4o-mini")
                .temperature(0.2)
                .maxTokens(2048)
                .build();
    }
}
