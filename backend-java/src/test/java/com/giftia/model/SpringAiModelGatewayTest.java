package com.giftia.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.giftia.config.GiftiaProperties;
import com.giftia.web.ApiTelemetry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class SpringAiModelGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicBoolean firstTokenArrived = new AtomicBoolean();
    private final AtomicBoolean firstTokenBeforeFinalChunk = new AtomicBoolean();
    private final CountDownLatch firstTokenSignal = new CountDownLatch(1);
    private boolean verifyIncrementality;
    private boolean truncateStream;
    private HttpServer server;
    private GiftiaProperties properties;
    private ApiTelemetry telemetry;
    private int providerStatus = 200;
    private String terminalReason = "stop";

    @BeforeEach void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::chat);
        server.createContext("/v1/embeddings", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"object":"list","model":"BAAI/bge-m3","data":[
                    {"object":"embedding","index":1,"embedding":[0.0,1.0]},
                    {"object":"embedding","index":0,"embedding":[1.0,0.0]}],
                    "usage":{"prompt_tokens":2,"total_tokens":2}}
                    """);
        });
        server.createContext("/v1/models", exchange -> respond(exchange, 200, "{\"data\":[]}"));
        server.start();
        properties = new GiftiaProperties(new MockEnvironment().withProperty("CHAT_API_KEY", "unit-chat-key")
                .withProperty("EMBEDDING_API_KEY", "unit-embedding-key"));
        String address = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        properties.setChatBaseUrl(address);
        properties.setEmbeddingBaseUrl(address + "/embeddings");
        properties.setModelTimeoutSeconds(5);
        telemetry = new ApiTelemetry();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void synchronousChatUsesSpringAiAndFlattensDeepSeekOptions() throws IOException {
        var gateway = gateway();
        assertThat(gateway.complete("系统", "你好", 0.2)).isEqualTo("你好，今天怎么样？");
        assertThat(bodies.get(0).path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(bodies.get(0).path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(bodies.get(0).has("reasoning_effort")).isFalse();
        assertThat(bodies.get(0).path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(bodies.get(0).has("extra_body")).isFalse();
        assertThat(authorizations).containsExactly("Bearer unit-chat-key");
        assertThat(gateway.healthy()).isTrue();
        assertThat(((java.util.Map<?, ?>) telemetry.metrics().get("llm")).get("calls_total")).isEqualTo(1L);
    }

    @Test void streamDeliversChineseTokensBeforeProviderFinishes() throws IOException {
        verifyIncrementality = true;
        List<String> tokens = new ArrayList<>();
        gateway().stream(List.of(new ModelMessage("system", "固定规则"), new ModelMessage("user", "你好")), token -> {
            tokens.add(token);
            firstTokenArrived.set(true);
            firstTokenSignal.countDown();
        });
        assertThat(tokens).containsExactly("你好", "，", "朋友");
        assertThat(firstTokenBeforeFinalChunk).isTrue();
    }

    @Test void embeddingsUseSeparateCredentialAndPreserveInputOrder() throws IOException {
        assertThat(gateway().embed(List.of("苹果", "跑步"))).containsExactly(List.of(1.0, 0.0), List.of(0.0, 1.0));
        assertThat(authorizations).containsExactly("Bearer unit-embedding-key");
        assertThat(bodies.get(0).path("model").asText()).isEqualTo("BAAI/bge-m3");
    }

    @Test void multimodalPreservesFullDataUrlAndImageBytes() throws IOException {
        properties.setChatModel("gpt-4o");
        gateway().stream(List.of(new ModelMessage("user", "看图", "data:image/png;base64,AQID")), token -> {});
        JsonNode content = bodies.get(0).path("messages").get(0).path("content");
        assertThat(content.toString()).contains("data:image/png;base64,AQID").contains("看图");
    }

    @Test void upstreamFailureIsNotConvertedToSuccessfulReply() throws IOException {
        providerStatus = 401;
        var gateway = gateway();
        assertThatThrownBy(() -> gateway.complete("系统", "你好", 0)).isInstanceOf(RuntimeException.class);
    }

    @Test void cleanEofWithoutTerminalChunkIsReportedAsFailure() throws IOException {
        truncateStream = true;
        assertThatThrownBy(() -> gateway().stream(List.of(new ModelMessage("user", "你好")), token -> {}))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("中断");
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter", "tool_calls"})
    void truncatedOrNonTextCompletionIsReportedAsFailure(String reason) throws IOException {
        terminalReason = reason;
        assertThatThrownBy(() -> gateway().stream(List.of(new ModelMessage("user", "你好")), token -> {}))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("未正常完成");
    }

    @Test void streamingAuthorizationFailureIsPropagated() throws IOException {
        providerStatus = 401;
        assertThatThrownBy(() -> gateway().stream(List.of(new ModelMessage("user", "你好")), token -> {}))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"qwen-3.7plus", "glm-5v-Turbo", "KIMI-2.6", "o3", "gpt-4-turbo", "qwen3.7-plus"})
    void legacyVisionModelsRetainImageCapability(String model) throws IOException {
        properties.setChatModel(model);
        assertThat(gateway().info().get("multimodal")).isEqualTo(true);
    }

    @Test void explicitCapabilityOverrideControlsModelMetadataAndImageAcceptance() throws IOException {
        properties.setChatModel("gpt-4o");
        properties.setChatMultimodal("FALSE");
        var disabled = gateway();
        assertThat(disabled.info().get("multimodal")).isEqualTo(false);
        assertThatThrownBy(() -> disabled.stream(List.of(new ModelMessage("user", "看图", "AQID")), token -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        properties.setChatModel("custom-vision-provider-model");
        properties.setChatMultimodal("true");
        var enabled = gateway();
        assertThat(enabled.info().get("multimodal")).isEqualTo(true);
        enabled.stream(List.of(new ModelMessage("user", "看图", "data:image/png;base64,AQID")), token -> {});
        assertThat(bodies.get(0).path("messages").get(0).path("content").toString()).contains("data:image/png;base64,AQID");
    }

    @Test void invalidCapabilityModeFailsConfigurationInsteadOfSilentlyChangingBehavior() {
        assertThatThrownBy(() -> properties.setChatMultimodal("enabled"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CHAT_MULTIMODAL");
    }

    @Test void deepSeekStreamingKeepsLegacyThinkingDefaultAndExplicitDisable() throws IOException {
        assertThat(properties.isThinkingEnabled()).isTrue();
        var gateway = gateway();
        gateway.stream(List.of(new ModelMessage("user", "你好")), token -> {});
        JsonNode enabled = bodies.get(0);
        assertThat(enabled.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(enabled.path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(enabled.path("temperature").asDouble()).isEqualTo(1.0);
        assertThat(enabled.has("top_p")).isFalse();
        properties.setThinkingEnabled(false);
        gateway.stream(List.of(new ModelMessage("user", "你好")), token -> {});
        JsonNode disabled = bodies.get(1);
        assertThat(disabled.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(disabled.path("temperature").asDouble()).isEqualTo(0.8);
        assertThat(disabled.has("reasoning_effort")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"glm-4.6v-flash", "glm-5.1", "qwen-3.7plus", "Qwen/Qwen2.5-72B-Instruct"})
    void supportedThinkingProfilesKeepTheirProviderParameters(String model) throws IOException {
        properties.setChatModel(model);
        gateway().stream(List.of(new ModelMessage("user", "你好")), token -> {});
        JsonNode body = bodies.get(0);
        assertThat(body.path("temperature").asDouble()).isEqualTo(1.0);
        if (model.toLowerCase().startsWith("qwen")) {
            assertThat(body.path("enable_thinking").asBoolean()).isTrue();
            assertThat(body.path("return_reasoning").asBoolean()).isTrue();
        } else assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o", "custom-model", "glm-5v-Turbo"})
    void unknownAndOrdinaryOpenAiModelsDoNotReceiveThinkingParameters(String model) throws IOException {
        properties.setChatModel(model);
        gateway().stream(List.of(new ModelMessage("user", "你好")), token -> {});
        JsonNode body = bodies.get(0);
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("enable_thinking")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.8);
    }

    @Test void textOnlyModelRejectsImagesAndMissingKeysStayUnconfigured() throws IOException {
        var gateway = gateway();
        assertThatThrownBy(() -> gateway.stream(List.of(new ModelMessage("user", "图片", "AQID")), token -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        var empty = new GiftiaProperties(new MockEnvironment());
        var unconfigured = new SpringAiModelGateway(empty, mapper, telemetry);
        assertThat(unconfigured.configured()).isFalse();
        assertThat(unconfigured.healthy()).isFalse();
    }

    @Test void customProviderPathsAvoidDuplicateVersionSegments() {
        assertThat(SpringAiModelGateway.endpoint("https://open.bigmodel.cn/api/paas/v4", "chat/completions").path())
                .isEqualTo("/api/paas/v4/chat/completions");
        assertThat(SpringAiModelGateway.endpoint("https://api.siliconflow.cn/v1/embeddings", "embeddings").path())
                .isEqualTo("/v1/embeddings");
        assertThatThrownBy(() -> SpringAiModelGateway.endpoint("https://user:secret@example.com/v1", "embeddings"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SpringAiModelGateway gateway() throws IOException { return new SpringAiModelGateway(properties, mapper, telemetry); }

    private void chat(HttpExchange exchange) throws IOException {
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        JsonNode body = mapper.readTree(exchange.getRequestBody());
        bodies.add(body);
        if (providerStatus != 200) { respond(exchange, providerStatus, "{\"error\":{\"message\":\"unauthorized\"}}"); return; }
        if (!body.path("stream").asBoolean()) {
            respond(exchange, 200, """
                    {"id":"test","object":"chat.completion","created":1,"model":"deepseek-v4-flash",
                    "choices":[{"index":0,"message":{"role":"assistant","content":"你好，今天怎么样？"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":2,"completion_tokens":2,"total_tokens":4}}
                    """);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (var output = exchange.getResponseBody()) {
            output.write(chunk("你好", null).getBytes(StandardCharsets.UTF_8));
            output.write(chunk("，", null).getBytes(StandardCharsets.UTF_8));
            output.flush();
            if (truncateStream) return;
            if (verifyIncrementality) {
                try { firstTokenSignal.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                firstTokenBeforeFinalChunk.set(firstTokenArrived.get());
            }
            output.write(chunk("朋友", terminalReason).getBytes(StandardCharsets.UTF_8));
            output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
    private String chunk(String content, String finish) throws IOException {
        return "data: " + mapper.writeValueAsString(java.util.Map.of("id", "test", "object", "chat.completion.chunk",
                "created", 1, "model", "test", "choices", List.of(
                        mapper.createObjectNode().put("index", 0).put("finish_reason", finish)
                                .set("delta", mapper.createObjectNode().put("content", content))))) + "\n\n";
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
