package com.giftia.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.giftia.config.GiftiaProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiSecurityFilterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private GiftiaProperties properties;
    private ApiTelemetry telemetry;
    private ApiSecurityFilter filter;
    private AtomicLong clock;
    private AtomicInteger handled;

    @BeforeEach
    void setup() {
        properties = mock(GiftiaProperties.class);
        when(properties.getAccessKey()).thenReturn("access-key");
        when(properties.getAdminKey()).thenReturn("admin-key");
        telemetry = new ApiTelemetry();
        clock = new AtomicLong(1);
        handled = new AtomicInteger();
        filter = new ApiSecurityFilter(properties, mapper, telemetry, clock::get, 1000, 30);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/v1"})
    void keepsPublicHealthAccessAndAdminKeysSeparate(String prefix) throws Exception {
        assertThat(invoke("GET", prefix + "/health", null, null, "ip").getStatus()).isEqualTo(200);
        MockHttpServletResponse unauthorized = invoke("GET", prefix + "/conversations/alice", null, null, "ip");
        assertThat(unauthorized.getStatus()).isEqualTo(401);
        assertThat(json(unauthorized).path("detail").asText()).isEqualTo("无效的访问密钥");
        assertThat(invoke("GET", prefix + "/conversations/alice", "wrong", null, "ip").getStatus()).isEqualTo(401);
        assertThat(invoke("GET", prefix + "/conversations/alice", "access-key", null, "ip").getStatus()).isEqualTo(200);
        assertThat(invoke("GET", prefix + "/logs", "access-key", null, "ip").getStatus()).isEqualTo(403);
        assertThat(invoke("GET", prefix + "/metrics", null, "wrong", "ip").getStatus()).isEqualTo(403);
        assertThat(invoke("GET", prefix + "/logs", null, "admin-key", "ip").getStatus()).isEqualTo(200);
        assertThat(invoke("GET", prefix + "/metrics", null, "admin-key", "ip").getStatus()).isEqualTo(200);
        assertThat(invoke("GET", prefix + "/config/prompt", "access-key", null, "ip").getStatus()).isEqualTo(200);
        assertThat(invoke("PUT", prefix + "/config/prompt", "access-key", null, "ip").getStatus()).isEqualTo(403);
        assertThat(invoke("PUT", prefix + "/config/prompt", null, "admin-key", "ip").getStatus()).isEqualTo(200);
        assertThat(handled.get()).isEqualTo(6);
    }

    @Test
    void administratorFallbackUsesTheAdminHeaderAndBothUnsetKeysAllowLocalUse() throws Exception {
        when(properties.getAdminKey()).thenReturn("");
        assertThat(invoke("GET", "/api/logs", "access-key", null, "ip").getStatus()).isEqualTo(403);
        assertThat(invoke("GET", "/api/logs", null, "access-key", "ip").getStatus()).isEqualTo(200);
        when(properties.getAccessKey()).thenReturn("");
        assertThat(invoke("GET", "/api/logs", null, null, "ip").getStatus()).isEqualTo(200);
        assertThat(invoke("GET", "/api/characters/alice", null, null, "ip").getStatus()).isEqualTo(200);
    }

    @Test
    void optionsAndNonApiPathsReachTheChainWithoutAuthentication() throws Exception {
        assertThat(invoke("OPTIONS", "/api/conversations/alice", null, null, "ip").getStatus()).isEqualTo(200);
        assertThat(invoke("GET", "/index.html", null, null, "ip").getStatus()).isEqualTo(200);
        assertThat(mapper.valueToTree(telemetry.metrics()).path("endpoints")).isEmpty();
    }

    @Test
    void chatQuotaIsPerUserSharedByAliasesAndExpiresWithoutSleeping() throws Exception {
        filter = new ApiSecurityFilter(properties, mapper, telemetry, clock::get, 1, 2);
        assertThat(invoke("POST", "/api/chat/alice", "access-key", null, "same-ip").getStatus()).isEqualTo(200);
        assertThat(invoke("POST", "/api/v1/chat/alice", "access-key", null, "different-ip").getStatus()).isEqualTo(200);
        MockHttpServletResponse limited = invoke("POST", "/api/chat/alice", "access-key", null, "third-ip");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(json(limited).path("detail").asText()).contains("过于频繁");
        assertThat(invoke("POST", "/api/chat/bob", "access-key", null, "same-ip").getStatus()).isEqualTo(200);
        clock.addAndGet(60_000_000_000L);
        assertThat(invoke("POST", "/api/chat/alice", "access-key", null, "same-ip").getStatus()).isEqualTo(200);
    }

    @Test
    void ordinaryQuotaIsPerIpAndDoesNotConsumeChatAllowance() throws Exception {
        filter = new ApiSecurityFilter(properties, mapper, telemetry, clock::get, 2, 1);
        assertThat(invoke("GET", "/api/health", null, null, "first-ip").getStatus()).isEqualTo(200);
        assertThat(invoke("GET", "/api/v1/health", null, null, "first-ip").getStatus()).isEqualTo(200);
        assertThat(invoke("GET", "/api/health", null, null, "first-ip").getStatus()).isEqualTo(429);
        assertThat(invoke("GET", "/api/health", null, null, "second-ip").getStatus()).isEqualTo(200);
        assertThat(invoke("POST", "/api/chat/alice", "access-key", null, "first-ip").getStatus()).isEqualTo(200);
        assertThat(invoke("POST", "/api/chat/alice", "access-key", null, "second-ip").getStatus()).isEqualTo(429);
    }

    @Test
    void recordsRejectedAndSuccessfulRequestsExactlyOnceWithoutUserIds() throws Exception {
        invoke("GET", "/api/conversations/alice", null, null, "ip");
        invoke("GET", "/api/conversations/bob", "access-key", null, "ip");
        JsonNode metrics = mapper.valueToTree(telemetry.metrics());
        assertThat(metrics.path("endpoints")).hasSize(1);
        JsonNode endpoint = metrics.path("endpoints").get(0);
        assertThat(endpoint.path("path").asText()).isEqualTo("/api/conversations/{id}");
        assertThat(endpoint.path("requests_total").asLong()).isEqualTo(2);
        assertThat(endpoint.path("requests_errors").asLong()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCannotExceedTheSameSlidingWindowQuota() throws Exception {
        ApiSecurityFilter.SlidingWindowLimiter limiter = new ApiSecurityFilter.SlidingWindowLimiter(30, clock::get);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int index = 0; index < 100; index++) requests.add(() -> limiter.allow("alice"));
            long accepted = 0;
            for (var request : pool.invokeAll(requests)) if (request.get()) accepted++;
            assertThat(accepted).isEqualTo(30);
            assertThat(limiter.allow("bob")).isTrue();
            clock.addAndGet(60_000_000_000L);
            assertThat(limiter.allow("alice")).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void waitsForAsyncStreamCompletionBeforeRecordingDurationExactlyOnce() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/alice");
        request.addHeader("X-Access-Key", "access-key");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> req.startAsync(req, res));
        assertThat(mapper.valueToTree(telemetry.metrics()).path("endpoints")).isEmpty();
        request.getAsyncContext().complete();
        JsonNode endpoints = mapper.valueToTree(telemetry.metrics()).path("endpoints");
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).path("path").asText()).isEqualTo("/api/chat/{id}");
        assertThat(endpoints.get(0).path("requests_total").asLong()).isEqualTo(1);
    }

    private MockHttpServletResponse invoke(String method, String path, String access, String admin, String remote)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remote);
        if (access != null) request.addHeader("X-Access-Key", access);
        if (admin != null) request.addHeader("X-Admin-Key", admin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            handled.incrementAndGet();
            res.getWriter().write("{}");
        });
        return response;
    }

    private JsonNode json(MockHttpServletResponse response) throws IOException {
        return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
}
