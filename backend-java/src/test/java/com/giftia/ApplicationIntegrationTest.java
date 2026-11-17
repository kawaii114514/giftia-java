package com.giftia;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.giftia.config.GiftiaProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Starts the real Spring container and real SQLite on an isolated temporary directory. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "giftia.chat-model=unit-test-model", "giftia.chat-base-url=http://127.0.0.1:1/v1",
        "giftia.access-key=unit-access", "giftia.admin-key=unit-admin", "CHAT_API_KEY=unit-key",
        "EMBEDDING_API_KEY=unit-embedding-key", "giftia.model-timeout-seconds=1"})
@DirtiesContext
class ApplicationIntegrationTest {
    private static final Path DATA = temporaryDirectory();
    @DynamicPropertySource static void data(DynamicPropertyRegistry registry) {
        registry.add("giftia.data-dir", DATA::toString);
        registry.add("giftia.database", () -> DATA.resolve("integration.db").toString());
    }
    @Autowired private TestRestTemplate http;
    @Autowired private GiftiaProperties properties;

    @Test void startsWithBoundConfigurationAndCompatibleProtectedApis() {
        assertThat(properties.getDataDir()).isEqualTo(DATA);
        assertThat(properties.getChatModel()).isEqualTo("unit-test-model");
        var health = http.getForEntity("/api/health", JsonNode.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody().path("auth_required").asBoolean()).isTrue();
        assertThat(http.getForEntity("/api/users", String.class).getStatusCode().value()).isEqualTo(401);
        var created = http.exchange("/api/users?user_id=integration-user", HttpMethod.POST, request(null), JsonNode.class);
        assertThat(created.getBody().path("user_id").asText()).isEqualTo("integration-user");
        var conversations = http.exchange("/api/v1/conversations/integration-user", HttpMethod.GET, request(null), JsonNode.class);
        assertThat(conversations.getBody().path("conversations")).hasSize(1);
        var cards = http.exchange("/api/characters/integration-user", HttpMethod.GET, request(null), JsonNode.class);
        assertThat(cards.getBody().path("presets")).hasSize(5);
        assertThat(cards.getBody().path("system_rules").asText()).contains("铁律");
        var model = http.exchange("/api/config/model", HttpMethod.GET, request(null), JsonNode.class);
        assertThat(model.getBody().path("model").asText()).isEqualTo("unit-test-model");
        var memory = http.exchange("/api/memory/integration-user/detail", HttpMethod.GET, request(null), JsonNode.class);
        assertThat(memory.getBody().path("layers").has("core")).isTrue();
    }
    private HttpEntity<Object> request(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Access-Key", "unit-access");
        return new HttpEntity<>(body, headers);
    }
    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("giftia-spring-integration-").toAbsolutePath(); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
    }
}
