package com.nytryx.burnai.service;

import java.util.Map;
import java.util.function.Consumer;

/** 统一的 OpenAI-compatible AI 接口，千问和 vLLM 均通过它接入。 */
public interface AiModelClient {
    boolean available();
    Map<String, Object> askJson(String systemPrompt, String userPrompt);
    Map<String, Object> askJsonStreaming(String systemPrompt, String userPrompt, Consumer<String> onTextDelta);
    String askTextStreaming(String systemPrompt, String userPrompt, Consumer<String> onTextDelta);
}
