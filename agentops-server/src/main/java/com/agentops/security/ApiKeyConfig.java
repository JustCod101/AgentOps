package com.agentops.security;

import com.agentops.domain.entity.ApiKey;
import com.agentops.repository.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;

@Configuration
public class ApiKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CommandLineRunner initializeApiKey(ApiKeyRepository apiKeyRepository) {
        return args -> {
            String defaultApiKey = System.getenv("AGENTOPS_API_KEY");
            if (defaultApiKey == null || defaultApiKey.isBlank()) {
                log.warn("AGENTOPS_API_KEY not set - using default dev key 'agentops-dev-key'");
                defaultApiKey = "agentops-dev-key";
            }

            String keyHash = hashApiKey(defaultApiKey);

            Optional<ApiKey> existing = apiKeyRepository.findByKeyHash(keyHash);
            if (existing.isEmpty()) {
                ApiKey apiKey = ApiKey.builder()
                        .name("default")
                        .keyHash(keyHash)
                        .active(true)
                        .createdAt(Instant.now())
                        .build();
                apiKeyRepository.save(apiKey);
                log.info("Default API key initialized (hash: {})", keyHash.substring(0, 16) + "...");
            }
        };
    }

    public static String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
