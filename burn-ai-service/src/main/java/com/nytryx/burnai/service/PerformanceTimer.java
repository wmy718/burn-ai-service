package com.nytryx.burnai.service;

import com.nytryx.burnai.model.StageTiming;

import java.util.ArrayList;
import java.util.List;

public final class PerformanceTimer {
    private final long startedNanos = System.nanoTime();
    private final List<StageTiming> timings = new ArrayList<>();

    public Stage stage(String name) {
        return new Stage(name);
    }

    public List<StageTiming> timings() {
        return List.copyOf(timings);
    }

    public long elapsedMs() {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    public final class Stage implements AutoCloseable {
        private final String name;
        private final long stageStarted = System.nanoTime();

        private Stage(String name) {
            this.name = name;
        }

        @Override
        public void close() {
            timings.add(new StageTiming(name, (System.nanoTime() - stageStarted) / 1_000_000L));
        }
    }
}
