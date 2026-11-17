package com.giftia.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.giftia.config.GiftiaProperties;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiSecurityFilter extends OncePerRequestFilter {
    private final GiftiaProperties properties;
    private final ObjectMapper mapper;
    private final ApiTelemetry telemetry;
    private final SlidingWindowLimiter general;
    private final SlidingWindowLimiter chat;

    @Autowired
    public ApiSecurityFilter(GiftiaProperties properties, ObjectMapper mapper, ApiTelemetry telemetry) {
        this(properties, mapper, telemetry, System::nanoTime, 60, 30);
    }

    ApiSecurityFilter(GiftiaProperties properties, ObjectMapper mapper, ApiTelemetry telemetry,
                      LongSupplier clock, int generalLimit, int chatLimit) {
        this.properties = properties;
        this.mapper = mapper;
        this.telemetry = telemetry;
        general = new SlidingWindowLimiter(generalLimit, clock);
        chat = new SlidingWindowLimiter(chatLimit, clock);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || request.getMethod().equals("OPTIONS");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        String path = request.getRequestURI();
        String endpoint = path.replaceFirst("^/api(?:/v1)?", "");
        AtomicBoolean recorded = new AtomicBoolean();
        Runnable record = () -> {
            if (recorded.compareAndSet(false, true))
                telemetry.recordRequest(path, response.getStatus(), System.nanoTime() - start);
        };
        try {
            boolean isChat = endpoint.startsWith("/chat/") && request.getMethod().equals("POST");
            String rateKey = isChat ? endpoint.substring("/chat/".length()) : request.getRemoteAddr();
            if (!(isChat ? chat : general).allow(rateKey)) {
                failure(response, 429, "请求过于频繁，请稍后再试");
                return;
            }
            if (!endpoint.equals("/health")) {
                boolean admin = endpoint.equals("/logs") || endpoint.equals("/metrics")
                        || (endpoint.equals("/config/prompt") && request.getMethod().equals("PUT"));
                String expected = admin ? fallback(properties.getAdminKey(), properties.getAccessKey())
                        : properties.getAccessKey();
                String supplied = request.getHeader(admin ? "X-Admin-Key" : "X-Access-Key");
                if (!blank(expected) && !matches(expected, supplied)) {
                    failure(response, admin ? 403 : 401, admin ? "需要管理员密钥" : "无效的访问密钥");
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            if (request.isAsyncStarted()) {
                try {
                    request.getAsyncContext().addListener(new AsyncListener() {
                        @Override public void onComplete(AsyncEvent event) { record.run(); }
                        @Override public void onTimeout(AsyncEvent event) { record.run(); }
                        @Override public void onError(AsyncEvent event) { record.run(); }
                        @Override public void onStartAsync(AsyncEvent event) { }
                    });
                } catch (IllegalStateException completedAlready) {
                    record.run();
                }
            } else record.run();
        }
    }

    private void failure(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), Map.of("detail", detail));
    }

    private static String fallback(String first, String second) { return blank(first) ? second : first; }
    private static boolean blank(String value) { return value == null || value.isEmpty(); }
    private static boolean matches(String expected, String supplied) {
        return supplied != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    static final class SlidingWindowLimiter {
        private static final long WINDOW = 60_000_000_000L;
        private final int maximum;
        private final LongSupplier clock;
        private final ConcurrentHashMap<String, ArrayDeque<Long>> requests = new ConcurrentHashMap<>();

        SlidingWindowLimiter(int maximum, LongSupplier clock) {
            this.maximum = maximum;
            this.clock = clock;
        }

        boolean allow(String key) {
            long now = clock.getAsLong();
            ArrayDeque<Long> window = requests.computeIfAbsent(key == null ? "unknown" : key,
                    ignored -> new ArrayDeque<>());
            synchronized (window) {
                while (!window.isEmpty() && window.peekFirst() <= now - WINDOW) window.removeFirst();
                if (window.size() >= maximum) return false;
                window.addLast(now);
                return true;
            }
        }
    }
}
