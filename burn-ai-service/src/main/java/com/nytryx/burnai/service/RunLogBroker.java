package com.nytryx.burnai.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class RunLogBroker {
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> backlog = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public RunLogBroker() {
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(String requestId) {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        emitters.computeIfAbsent(requestId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(requestId, emitter));
        emitter.onTimeout(() -> remove(requestId, emitter));
        sendOne(emitter, event(requestId, "实时日志连接已建立"));
        for (Map<String, Object> event : backlog.getOrDefault(requestId, List.of())) {
            sendOne(emitter, event);
        }
        return emitter;
    }

    public void publish(String requestId, String message) {
        Map<String, Object> event = event(requestId, message);
        backlog.computeIfAbsent(requestId, ignored -> new CopyOnWriteArrayList<>()).add(event);
        for (SseEmitter emitter : emitters.getOrDefault(requestId, List.of())) {
            sendOne(emitter, event);
        }
    }

    private Map<String, Object> event(String requestId, String message) {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("requestId", requestId);
        event.put("message", message);
        event.put("timestamp", java.time.OffsetDateTime.now().toString());
        return event;
    }

    public List<Map<String, Object>> events(String requestId) {
        return List.copyOf(backlog.getOrDefault(requestId, List.of()));
    }

    private void sendOne(SseEmitter emitter, Map<String, Object> event) {
        try {
            emitter.send(SseEmitter.event().name("ai-progress").data(event));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
    }

    private void sendHeartbeats() {
        emitters.forEach((requestId, requestEmitters) -> requestEmitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException exception) {
                remove(requestId, emitter);
                emitter.complete();
            }
        }));
    }

    private void remove(String requestId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(requestId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeatExecutor.shutdownNow();
    }
}
