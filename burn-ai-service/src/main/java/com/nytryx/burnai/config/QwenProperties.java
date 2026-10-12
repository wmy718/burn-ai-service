package com.nytryx.burnai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "burn-ai.qwen")
public record QwenProperties(
        boolean enabled,
        String apiKey,
        String endpoint,
        String model,
        int timeoutSeconds
) {
    public boolean available() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
