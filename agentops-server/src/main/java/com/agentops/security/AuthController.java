package com.agentops.security;

import com.agentops.repository.ApiKeyRepository;
import com.agentops.security.JwtTokenProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final ApiKeyRepository apiKeyRepository;

    public AuthController(JwtTokenProvider jwtTokenProvider, ApiKeyRepository apiKeyRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.apiKeyRepository = apiKeyRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String apiKey = request.get("apiKey");

        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "apiKey is required"));
        }

        String keyHash = ApiKeyConfig.hashApiKey(apiKey);

        return apiKeyRepository.findByKeyHashAndActiveTrue(keyHash)
                .map(apiKeyEntity -> {
                    apiKeyEntity.setLastUsedAt(Instant.now());
                    apiKeyRepository.save(apiKeyEntity);

                    String token = jwtTokenProvider.generateToken(apiKeyEntity.getName());
                    long expiresIn = jwtTokenProvider.getExpirationSeconds();

                    return ResponseEntity.ok(Map.of(
                            "token", token,
                            "expiresIn", expiresIn,
                            "tokenType", "Bearer"
                    ));
                })
                .orElse(ResponseEntity.status(401)
                        .body(Map.of("error", "Invalid API key")));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing or invalid Authorization header"));
        }

        String token = authHeader.substring(7);

        if (!jwtTokenProvider.validateToken(token)) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid or expired token"));
        }

        String subject = jwtTokenProvider.getSubjectFromToken(token);
        String newToken = jwtTokenProvider.generateToken(subject);
        long expiresIn = jwtTokenProvider.getExpirationSeconds();

        return ResponseEntity.ok(Map.of(
                "token", newToken,
                "expiresIn", expiresIn,
                "tokenType", "Bearer"
        ));
    }
}
