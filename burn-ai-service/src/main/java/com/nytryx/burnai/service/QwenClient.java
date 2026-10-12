package com.nytryx.burnai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.config.QwenProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "burn-ai.provider", havingValue = "qwen", matchIfMissing = true)
public class QwenClient implements AiModelClient {
    private final QwenProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public QwenClient(QwenProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.max(5, properties.timeoutSeconds())));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(5, properties.timeoutSeconds())));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public boolean available() {
        return properties.available();
    }

    public Map<String, Object> askJson(String systemPrompt, String userPrompt) {
        if (!available()) {
            return Map.of();
        }
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        Map<String, Object> response = restClient.post()
                .uri(properties.endpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.apiKey())
                .body(body)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {
                });
        String content = extractContent(response);
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        content = content.trim();
        if (content.startsWith("```")) {
            int line = content.indexOf('\n');
            int end = content.lastIndexOf("```");
            if (line >= 0 && end > line) content = content.substring(line + 1, end).trim();
        }
        return parseContent(content);
    }

    public Map<String, Object> askJsonStreaming(String systemPrompt, String userPrompt, Consumer<String> onTextDelta) {
        if (!available()) return Map.of();
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "temperature", 0,
                "stream", true,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        try {
            return restClient.post()
                    .uri(properties.endpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .body(body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) throw new IllegalStateException("Qwen stream HTTP " + response.getStatusCode());
                        StringBuilder content = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) continue;
                                String data = line.substring(5).trim();
                                if (data.isBlank() || "[DONE]".equals(data)) continue;
                                Map<String, Object> chunk = objectMapper.readValue(data, new TypeReference<>() {});
                                String delta = extractDeltaContent(chunk);
                                if (delta != null && !delta.isEmpty()) {
                                    content.append(delta);
                                    onTextDelta.accept(delta);
                                }
                            }
                        }
                        return parseContent(content.toString());
                    });
        } catch (RuntimeException exception) {
            return askJson(systemPrompt, userPrompt);
        }
    }

    /**
     * Streams plain text exactly as it is produced by the model. The callback is
     * invoked for every non-empty provider delta, while the returned value is the
     * complete text assembled from those deltas.
     */
    public String askTextStreaming(String systemPrompt, String userPrompt, Consumer<String> onTextDelta) {
        if (!available()) return "";
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "temperature", 0,
                "stream", true,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        return restClient.post()
                .uri(properties.endpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.apiKey())
                .body(body)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new IllegalStateException("Qwen stream HTTP " + response.getStatusCode());
                    }
                    StringBuilder content = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) continue;
                            String data = line.substring(5).trim();
                            if (data.isBlank() || "[DONE]".equals(data)) continue;
                            Map<String, Object> chunk = objectMapper.readValue(data, new TypeReference<>() {});
                            String delta = extractDeltaContent(chunk);
                            if (delta != null && !delta.isEmpty()) {
                                content.append(delta);
                                onTextDelta.accept(delta);
                            }
                        }
                    }
                    return content.toString();
                });
    }

    private Map<String, Object> parseContent(String content) {
        if (content == null || content.isBlank()) return Map.of();
        content = content.trim();
        if (content.startsWith("```")) {
            int line = content.indexOf('\n');
            int end = content.lastIndexOf("```");
            if (line >= 0 && end > line) content = content.substring(line + 1, end).trim();
        }
        try {
            return objectMapper.readValue(content, new TypeReference<>() {});
        } catch (Exception exception) {
            return Map.of("raw", content);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        Object choices = response == null ? null : response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) {
            return null;
        }
        Object message = firstMap.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) {
            return null;
        }
        Object content = messageMap.get("content");
        return content == null ? null : content.toString();
    }

    private String extractDeltaContent(Map<String, Object> response) {
        Object choices = response == null ? null : response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) return null;
        Object delta = firstMap.get("delta");
        if (!(delta instanceof Map<?, ?> deltaMap)) return null;
        Object content = deltaMap.get("content");
        return content == null ? null : content.toString();
    }
}
