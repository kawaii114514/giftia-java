package com.giftia.web;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Bounded application log and real request/model counters used by the settings UI. */
@Component
public class ApiTelemetry {
    private final long started = System.nanoTime();
    private final Map<String, Counter> requests = new ConcurrentHashMap<>();
    private final ArrayDeque<Map<String, Object>> logs = new ArrayDeque<>();
    private final LongAdder modelCalls = new LongAdder();
    private final LongAdder modelNanos = new LongAdder();
    private AppenderBase<ILoggingEvent> appender;

    @PostConstruct
    void attachLogs() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) return;
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                addLog(event.getLevel().toString(), event.getFormattedMessage());
            }
        };
        appender.setContext(context);
        appender.start();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        addLog("INFO", "Giftia Spring Boot 服务已启动");
    }

    @PreDestroy
    void detachLogs() {
        if (appender == null) return;
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context)
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
    }

    public synchronized void addLog(String level, String message) {
        // Logs may contain upstream error strings. Redact token-like values before retention.
        String safe = message.replaceAll("(?i)(Bearer\\s+)[^\\s,;\\\"]+", "$1[redacted]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]{8,}\\b", "[redacted]");
        if (logs.size() == 500) logs.removeFirst();
        logs.addLast(Map.of("timestamp", Instant.now().toEpochMilli(), "level", level,
                "message", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        + " [" + level + "] " + safe));
    }

    public synchronized List<Map<String, Object>> logs(int limit) {
        List<Map<String, Object>> snapshot = new ArrayList<>(logs);
        return List.copyOf(snapshot.subList(Math.max(0, snapshot.size() - limit), snapshot.size()));
    }

    public void recordRequest(String path, int status, long nanos) {
        Counter counter = requests.computeIfAbsent(normalize(path), ignored -> new Counter());
        counter.total.increment();
        counter.nanos.add(Math.max(0, nanos));
        if (status >= 400) counter.errors.increment();
    }

    public void recordModelCall(long elapsedNanos) {
        modelCalls.increment();
        modelNanos.add(Math.max(0, elapsedNanos));
    }

    public Map<String, Object> metrics() {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        requests.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).forEach(entry -> {
            Counter count = entry.getValue();
            long total = count.total.sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", entry.getKey());
            row.put("requests_total", total);
            row.put("requests_errors", count.errors.sum());
            row.put("avg_duration_ms", rounded(total == 0 ? 0 : count.nanos.sum() / 1_000_000.0 / total));
            endpoints.add(row);
        });
        long calls = modelCalls.sum();
        return Map.of("uptime_seconds", Math.round((System.nanoTime() - started) / 1_000_000_000.0),
                "endpoints", endpoints,
                "llm", Map.of("calls_total", calls, "avg_duration_ms",
                        rounded(calls == 0 ? 0 : modelNanos.sum() / 1_000_000.0 / calls)));
    }

    private static double rounded(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static String normalize(String path) {
        return path.replaceAll("^(/api(?:/v1)?)/(chat|conversations|memory|profile|characters)/[^/]+", "$1/$2/{id}")
                .replaceAll("(/conversations/\\{id})/[^/]+", "$1/{conversation_id}")
                .replaceAll("(/memory/\\{id})/[^/]+/layer$", "$1/{memory_id}/layer");
    }

    private static class Counter {
        private final LongAdder total = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder nanos = new LongAdder();
    }
}
