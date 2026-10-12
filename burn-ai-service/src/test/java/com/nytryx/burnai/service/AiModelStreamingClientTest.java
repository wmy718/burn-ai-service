package com.nytryx.burnai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nytryx.burnai.config.QwenProperties;
import com.nytryx.burnai.config.VllmProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiModelStreamingClientTest {
    @Test void vllmDisablesRawThinkingForDoctorFacingContent() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
            var client = new VllmClient(new VllmProperties(true,endpoint,"Qwen3.5-9B","EMPTY",5),new ObjectMapper());
            client.askTextStreaming("system","user",ignored -> {});
            var json = new ObjectMapper().readTree(requestBody.get());
            assertFalse(json.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
            assertEquals("Qwen3.5-9B",json.path("model").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test void qwenAndVllmBothForwardEveryTextDelta() throws Exception {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"code\\\":\\\"x\\\"}\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"\\n\"}}]}\n\n"
                + "data: [DONE]\n\n";
        HttpServer server = streamingServer(200, body, new AtomicInteger());
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
            var clients = List.<AiModelClient>of(
                    new QwenClient(new QwenProperties(true,"test-key",endpoint,"test-model",5),new ObjectMapper()),
                    new VllmClient(new VllmProperties(true,endpoint,"test-model","test-key",5),new ObjectMapper())
            );
            for (AiModelClient client : clients) {
                List<String> deltas = new ArrayList<>();
                String complete = client.askTextStreaming("system","user",deltas::add);
                assertEquals(List.of("{\"code\":\"x\"}","\n"),deltas);
                assertEquals("{\"code\":\"x\"}\n",complete);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test void vllmStreamingFailureDoesNotRetryThroughBlockingJson() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = streamingServer(500,"failed",requests);
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
            var client = new VllmClient(new VllmProperties(true,endpoint,"test-model","test-key",5),new ObjectMapper());
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> client.askTextStreaming("system","user",ignored -> {}));
            assertTrue(failure.getMessage().contains("failed"),
                    "vLLM HTTP error body should be preserved for diagnosis");
            assertEquals(1,requests.get(),"流式失败后不得静默改用一次性 JSON 请求");
        } finally {
            server.stop(0);
        }
    }

    @Test void vllmStructuredReportStreamKeepsItsExistingBlockingFallback() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat", exchange -> {
            int requestNumber = requests.incrementAndGet();
            int status = requestNumber == 1 ? 500 : 200;
            String body = requestNumber == 1 ? "failed"
                    : "{\"choices\":[{\"message\":{\"content\":\"{\\\"report\\\":\\\"ok\\\"}\"}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
            var client = new VllmClient(new VllmProperties(true,endpoint,"test-model","test-key",5),new ObjectMapper());
            assertEquals("ok",client.askJsonStreaming("system","user",ignored -> {}).get("report"));
            assertEquals(2,requests.get());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer streamingServer(int status, String body, AtomicInteger requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
