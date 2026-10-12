package com.nytryx.burnai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "burn-ai.vllm")
public record VllmProperties(boolean enabled, String endpoint, String model, String apiKey, int timeoutSeconds) {
    public boolean available() {
        return enabled && endpoint != null && !endpoint.isBlank();
    }
}
