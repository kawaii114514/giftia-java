package com.giftia.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTelemetryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsOnlyTheLatestFiveHundredLogsAndRedactsCredentials() {
        ApiTelemetry telemetry = new ApiTelemetry();
        for (int index = 0; index < 510; index++) telemetry.addLog("INFO", "entry " + index);
        assertThat(telemetry.logs(500)).hasSize(500);
        assertThat(telemetry.logs(500).get(0).get("message").toString()).endsWith("entry 10");
        assertThat(telemetry.logs(2)).hasSize(2);
        telemetry.addLog("ERROR", "Bearer credential-value; upstream sk-1234567890abcdef returned 401");
        String message = telemetry.logs(1).get(0).get("message").toString();
        assertThat(message).contains("[redacted]", "401").doesNotContain("credential-value", "sk-1234567890abcdef");
    }

    @Test
    void collectsActualRequestAndModelCountersAndNormalizesBothPrefixes() {
        ApiTelemetry telemetry = new ApiTelemetry();
        telemetry.recordRequest("/api/conversations/alice/c1", 200, 2_000_000);
        telemetry.recordRequest("/api/conversations/bob/c2", 404, 4_000_000);
        telemetry.recordRequest("/api/v1/memory/alice/m1/layer", 400, 1_000_000);
        telemetry.recordRequest("/api/v1/memory/bob/m2/layer", 200, 3_000_000);
        telemetry.recordModelCall(10_000_000);
        telemetry.recordModelCall(20_000_000);
        JsonNode metrics = mapper.valueToTree(telemetry.metrics());
        assertThat(metrics.path("uptime_seconds").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.path("endpoints")).hasSize(2);
        JsonNode conversations = metrics.path("endpoints").get(0);
        assertThat(conversations.path("path").asText()).isEqualTo("/api/conversations/{id}/{conversation_id}");
        assertThat(conversations.path("requests_total").asLong()).isEqualTo(2);
        assertThat(conversations.path("requests_errors").asLong()).isEqualTo(1);
        assertThat(conversations.path("avg_duration_ms").asDouble()).isEqualTo(3.0);
        assertThat(metrics.path("endpoints").get(1).path("path").asText()).isEqualTo("/api/v1/memory/{id}/{memory_id}/layer");
        assertThat(metrics.path("llm").path("calls_total").asLong()).isEqualTo(2);
        assertThat(metrics.path("llm").path("avg_duration_ms").asDouble()).isEqualTo(15.0);
    }

    @Test
    void capturesAndDetachesApplicationLogsWithTheLifecycle() {
        ApiTelemetry telemetry = new ApiTelemetry();
        telemetry.attachLogs();
        try {
            LoggerFactory.getLogger("giftia.telemetry.test").info("a real application log");
            assertThat(telemetry.logs(500)).anySatisfy(row ->
                    assertThat(row.get("message").toString()).contains("a real application log"));
        } finally {
            telemetry.detachLogs();
        }
        int count = telemetry.logs(500).size();
        LoggerFactory.getLogger("giftia.telemetry.test").info("after detached");
        assertThat(telemetry.logs(500)).hasSize(count);
    }
}
