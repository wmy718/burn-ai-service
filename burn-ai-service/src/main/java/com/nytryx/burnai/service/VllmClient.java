package com.nytryx.burnai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.config.VllmProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/** vLLM OpenAI-compatible API 客户端。通过 burn-ai.provider=vllm 启用。 */
@Service
@ConditionalOnProperty(name = "burn-ai.provider", havingValue = "vllm")
public class VllmClient implements AiModelClient {
    private static final Map<String, Object> NON_THINKING_CHAT_TEMPLATE = Map.of("enable_thinking", false);
    private final VllmProperties properties;
    private final ObjectMapper mapper;
    private final RestClient restClient;

    public VllmClient(VllmProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        Duration timeout = Duration.ofSeconds(Math.max(5, properties.timeoutSeconds()));
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public boolean available() { return properties.available(); }

    public Map<String, Object> askJson(String systemPrompt, String userPrompt) {
        if (!available()) return Map.of();
        Map<String, Object> body = Map.of("model", properties.model(), "temperature", 0,
                "chat_template_kwargs", NON_THINKING_CHAT_TEMPLATE,
                "response_format", Map.of("type", "json_object"), "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        Map<String, Object> response = restClient.post().uri(properties.endpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.apiKey())
                .body(body).retrieve().body(new org.springframework.core.ParameterizedTypeReference<>() {});
        Object choices = response == null ? null : response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> first)) return Map.of();
        Object message = first.get("message");
        if (!(message instanceof Map<?, ?> msg) || msg.get("content") == null) return Map.of();
        try { return mapper.readValue(msg.get("content").toString(), new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of("raw", msg.get("content").toString()); }
    }

    public Map<String, Object> askJsonStreaming(String systemPrompt, String userPrompt, Consumer<String> onTextDelta) {
        String content;
        try {
            content = stream(systemPrompt, userPrompt, onTextDelta, true);
        } catch (RuntimeException exception) {
            return askJson(systemPrompt, userPrompt);
        }
        if (content.isBlank()) return askJson(systemPrompt, userPrompt);
        try { return mapper.readValue(content, new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of("raw", content); }
    }

    public String askTextStreaming(String systemPrompt, String userPrompt, Consumer<String> onTextDelta) {
        return stream(systemPrompt, userPrompt, onTextDelta, false);
    }

    private String stream(String systemPrompt, String userPrompt, Consumer<String> onTextDelta, boolean json) {
        if (!available()) return "";
        Map<String, Object> body = json
                ? Map.of("model", properties.model(), "temperature", 0, "stream", true,
                    "chat_template_kwargs", NON_THINKING_CHAT_TEMPLATE,
                    "response_format", Map.of("type", "json_object"), "messages", messages(systemPrompt, userPrompt))
                : Map.of("model", properties.model(), "temperature", 0, "stream", true,
                    "chat_template_kwargs", NON_THINKING_CHAT_TEMPLATE,
                    "messages", messages(systemPrompt, userPrompt));
        return restClient.post().uri(properties.endpoint()).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.apiKey()).body(body)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        String errorBody;
                        try {
                            errorBody = new String(response.getBody().readNBytes(2048), StandardCharsets.UTF_8).trim();
                        } catch (IOException exception) {
                            errorBody = "<无法读取错误响应：" + exception.getClass().getSimpleName() + ">";
                        }
                        throw new IllegalStateException("vLLM stream HTTP " + response.getStatusCode()
                                + (errorBody.isBlank() ? "" : ": " + errorBody));
                    }
                    StringBuilder content = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) continue;
                            String data = line.substring(5).trim();
                            if (data.isBlank() || "[DONE]".equals(data)) continue;
                            Map<String, Object> chunk = mapper.readValue(data, new TypeReference<>() {});
                            String delta = extractDelta(chunk);
                            if (delta != null && !delta.isEmpty()) {
                                content.append(delta);
                                onTextDelta.accept(delta);
                            }
                        }
                    }
                    return content.toString();
                });
    }

    private List<Map<String, String>> messages(String systemPrompt, String userPrompt) {
        return List.of(Map.of("role", "system", "content", systemPrompt), Map.of("role", "user", "content", userPrompt));
    }

    private String extractDelta(Map<String, Object> response) {
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> first)) return null;
        Object delta = first.get("delta");
        if (!(delta instanceof Map<?, ?> map)) return null;
        Object content = map.get("content");
        return content == null ? null : content.toString();
    }
}
