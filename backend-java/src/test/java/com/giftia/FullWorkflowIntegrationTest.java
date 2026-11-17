package com.giftia;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real HTTP, Spring AI, workflow, background tasks and SQLite; only the upstream provider is local. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "giftia.chat-model=integration-model", "giftia.access-key=integration-access",
        "giftia.admin-key=integration-admin", "CHAT_API_KEY=local-chat-key",
        "EMBEDDING_API_KEY=local-embedding-key", "giftia.model-timeout-seconds=5",
        "giftia.analysis-timeout-seconds=5"})
@DirtiesContext
class FullWorkflowIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DATA = temporaryDirectory();
    private static final List<JsonNode> GENERATION_REQUESTS = new CopyOnWriteArrayList<>();
    private static final CountDownLatch FIRST_TOKEN_RECEIVED = new CountDownLatch(1);
    private static final AtomicBoolean WAIT_FOR_FIRST_CLIENT = new AtomicBoolean(true);
    private static final AtomicBoolean TOKEN_ARRIVED_BEFORE_FINAL_CHUNK = new AtomicBoolean();
    private static final ExecutorService PROVIDER_THREADS = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "local-model-integration");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpServer PROVIDER = provider();

    @DynamicPropertySource static void isolatedConfiguration(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/v1";
        registry.add("giftia.chat-base-url", () -> base);
        registry.add("giftia.embedding-base-url", () -> base + "/embeddings");
        registry.add("giftia.data-dir", DATA::toString);
        registry.add("giftia.database", () -> DATA.resolve("workflow.db").toString());
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate http;

    @AfterAll static void stopProvider() {
        PROVIDER.stop(0);
        PROVIDER_THREADS.shutdownNow();
    }

    @Test void completeConversationRoundTripPersistsMemoryRecallsAcrossConversationsAndKeepsUsersSeparate() throws Exception {
        assertThat(json(HttpMethod.POST, "/api/users?user_id=alice", null).path("ok").asBoolean()).isTrue();
        String firstConversation = json(HttpMethod.GET, "/api/conversations/alice", null).path("current_id").asText();
        List<JsonNode> first = chat("alice", firstConversation, "我叫青禾，我喜欢周末跑步。", "/api");
        assertThat(first.stream().map(event -> event.path("type").asText()))
                .containsSubsequence("status", "token", "reply", "done").doesNotContain("error");
        assertThat(TOKEN_ARRIVED_BEFORE_FINAL_CHUNK).isTrue();
        String firstReply = first.stream().filter(event -> event.path("type").asText().equals("reply"))
                .findFirst().orElseThrow().path("text").asText();
        String tokenText = String.join("", first.stream().filter(event -> event.path("type").asText().equals("token"))
                .map(event -> event.path("text").asText()).toList());
        assertThat(tokenText).isEqualTo(firstReply).contains("青禾", "跑步");
        assertThat(first.get(first.size() - 1).path("conversation_id").asText()).isEqualTo(firstConversation);
        JsonNode saved = json(HttpMethod.GET, "/api/conversations/alice/" + firstConversation, null);
        assertThat(saved.path("messages")).hasSize(2);
        assertThat(saved.path("messages").get(1).path("content").asText()).isEqualTo(firstReply);

        waitForMemory("alice");
        JsonNode details = json(HttpMethod.GET, "/api/memory/alice/detail", null);
        assertThat(details.path("layers").toString()).contains("青禾", "跑步");
        assertThat(details.path("working_memory").path("summary").asText()).contains("青禾", "跑步");
        JsonNode profile = json(HttpMethod.GET, "/api/profile/alice", null);
        assertThat(profile.path("profile").path("identity").path("name").asText()).isEqualTo("青禾");

        String secondConversation = json(HttpMethod.POST, "/api/v1/conversations/alice", null).path("id").asText();
        List<JsonNode> recall = chat("alice", secondConversation, "我叫什么，喜欢什么？", "/api/v1");
        assertThat(recall.stream().filter(event -> event.path("type").asText().equals("reply"))
                .findFirst().orElseThrow().path("text").asText()).contains("青禾", "跑步");
        JsonNode recallRequest = GENERATION_REQUESTS.get(1);
        assertThat(recallRequest.path("messages")).hasSize(2);
        assertThat(recallRequest.path("messages").get(0).path("content").asText())
                .contains("跨对话工作摘要", "用户档案", "检索到的用户记忆", "青禾");

        assertThat(json(HttpMethod.POST, "/api/users?user_id=bob", null).path("ok").asBoolean()).isTrue();
        String bobConversation = json(HttpMethod.GET, "/api/conversations/bob", null).path("current_id").asText();
        List<JsonNode> bob = chat("bob", bobConversation, "我叫什么，喜欢什么？", "/api");
        assertThat(bob.stream().filter(event -> event.path("type").asText().equals("reply"))
                .findFirst().orElseThrow().path("text").asText()).contains("不知道").doesNotContain("青禾", "跑步");
        assertThat(GENERATION_REQUESTS.get(2).path("messages").get(0).path("content").asText()).doesNotContain("青禾", "跑步");
        assertThat(json(HttpMethod.GET, "/api/profile/bob", null).path("profile").path("identity").has("name")).isFalse();

        JsonNode chosen = json(HttpMethod.PUT, "/api/characters/alice", Map.of("character_id", "zhixia"));
        assertThat(chosen.path("active_character").path("id").asText()).isEqualTo("zhixia");
        assertThat(json(HttpMethod.GET, "/api/v1/characters/alice", null).path("active_character").path("id").asText())
                .isEqualTo("zhixia");
        assertThat(json(HttpMethod.GET, "/api/characters/bob", null).path("active_character").path("id").asText())
                .isEqualTo("giftia");
        assertThat(Files.readString(DATA.resolve("character_config.json"))).contains("zhixia");

        // Clear also invalidates a queued/running post-reply update from the second turn.
        assertThat(json(HttpMethod.DELETE, "/api/memory/alice", null).path("ok").asBoolean()).isTrue();
        assertThat(json(HttpMethod.GET, "/api/memory/alice/stats", null).path("total").asInt()).isZero();
        JsonNode cleared = json(HttpMethod.GET, "/api/memory/alice/detail", null);
        assertThat(cleared.path("working_memory").path("summary").asText()).isEmpty();
        assertThat(json(HttpMethod.GET, "/api/profile/alice", null).path("profile").path("identity").size()).isZero();
        assertThat(json(HttpMethod.GET, "/api/conversations/alice/" + firstConversation, null).path("messages")).hasSize(2);
        assertThat(json(HttpMethod.GET, "/api/conversations/alice/" + secondConversation, null).path("messages")).hasSize(2);
        assertThat(json(HttpMethod.GET, "/api/characters/alice", null).path("active_character").path("id").asText())
                .isEqualTo("zhixia");
    }

    private List<JsonNode> chat(String user, String conversation, String message, String prefix) throws Exception {
        String body = JSON.writeValueAsString(Map.of("message", message, "conversation_id", conversation,
                "conversation_history", List.of()));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + prefix + "/chat/" + user))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .header("X-Access-Key", "integration-access")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        List<JsonNode> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                assertThat(line).startsWith("data: ");
                JsonNode event = JSON.readTree(line.substring(6));
                events.add(event);
                if (event.path("type").asText().equals("token")) FIRST_TOKEN_RECEIVED.countDown();
            }
        }
        return events;
    }

    private JsonNode json(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Access-Key", "integration-access");
        ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).as(method + " " + path).isEqualTo(200);
        return response.getBody();
    }

    private void waitForMemory(String user) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            JsonNode profile = json(HttpMethod.GET, "/api/profile/" + user, null);
            if (profile.path("profile").path("identity").path("name").asText().equals("青禾")) return;
            if (System.nanoTime() >= deadline) throw new AssertionError("Background memory did not finish within five seconds");
            Thread.sleep(200);
        }
    }

    private static HttpServer provider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(PROVIDER_THREADS);
            server.createContext("/v1/chat/completions", FullWorkflowIntegrationTest::completion);
            server.createContext("/v1/embeddings", exchange -> {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                List<Map<String, Object>> vectors = new ArrayList<>();
                int count = request.path("input").isArray() ? request.path("input").size() : 1;
                for (int i = 0; i < count; i++) vectors.add(Map.of("object", "embedding", "index", i, "embedding", List.of(1.0, 0.0, 0.0)));
                respond(exchange, JSON.writeValueAsString(Map.of("object", "list", "data", vectors,
                        "model", "BAAI/bge-m3", "usage", Map.of("prompt_tokens", 1, "total_tokens", 1))));
            });
            server.createContext("/v1/models", exchange -> respond(exchange, "{\"data\":[]}"));
            server.start();
            return server;
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private static void completion(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        if (request.path("stream").asBoolean()) {
            GENERATION_REQUESTS.add(request);
            String latest = request.path("messages").get(request.path("messages").size() - 1).path("content").asText();
            boolean knowsAlice = request.path("messages").get(0).path("content").asText().contains("青禾");
            String answer = latest.contains("我叫青禾") ? "青禾，我记住你喜欢周末跑步。"
                    : knowsAlice ? "你叫青禾，喜欢周末跑步。" : "我还不知道你的名字和爱好。";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(chunk(answer.substring(0, 1), null).getBytes(StandardCharsets.UTF_8));
                output.write(chunk(answer.substring(1, 2), null).getBytes(StandardCharsets.UTF_8));
                output.flush();
                if (WAIT_FOR_FIRST_CLIENT.compareAndSet(true, false)) {
                    try { TOKEN_ARRIVED_BEFORE_FINAL_CHUNK.set(FIRST_TOKEN_RECEIVED.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                output.write(chunk(answer.substring(2), "stop").getBytes(StandardCharsets.UTF_8));
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            return;
        }
        String system = request.path("messages").get(0).path("content").asText();
        String user = request.path("messages").get(1).path("content").asText();
        String content;
        if (system.contains("分析用户当前情绪"))
            content = "{\"current_emotion\":\"neutral\",\"emotion_intensity\":0.3,\"emotion_summary\":\"用户平静地聊天\"}";
        else if (system.contains("长期记忆事实提取"))
            content = user.contains("我叫青禾") ? "[\"用户叫青禾\",\"用户喜欢周末跑步\"]" : "[]";
        else if (system.contains("跨对话工作记忆更新"))
            content = user.contains("青禾")
                    ? "{\"summary\":\"用户叫青禾，喜欢周末跑步。\",\"open_topics\":[],\"current_emotion\":\"neutral\"}"
                    : "{\"summary\":\"尚不了解用户的个人信息。\",\"open_topics\":[],\"current_emotion\":\"neutral\"}";
        else if (system.contains("用户档案提取"))
            content = user.contains("我叫青禾") ? "{\"identity.name\":\"青禾\",\"preferences.hobbies\":[\"周末跑步\"]}" : "{}";
        else if (system.contains("查询改写")) content = "[\"用户的姓名\",\"用户的爱好\"]";
        else if (system.contains("记忆整理")) content = "{\"memory_context\":\"用户叫青禾，喜欢周末跑步。\",\"memory_summary\":\"姓名和爱好\"}";
        else content = "{}";
        respond(exchange, JSON.writeValueAsString(Map.of("id", "local-completion", "object", "chat.completion",
                "created", 1, "model", "integration-model", "choices", List.of(Map.of("index", 0,
                        "message", Map.of("role", "assistant", "content", content), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2))));
    }

    private static String chunk(String text, String finish) throws IOException {
        var choice = JSON.createObjectNode().put("index", 0).put("finish_reason", finish);
        choice.set("delta", JSON.createObjectNode().put("role", "assistant").put("content", text));
        return "data: " + JSON.writeValueAsString(Map.of("id", "local-stream", "object", "chat.completion.chunk",
                "created", 1, "model", "integration-model", "choices", List.of(choice))) + "\n\n";
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("giftia-workflow-integration-").toAbsolutePath(); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
    }
}
