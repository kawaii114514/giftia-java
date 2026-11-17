package com.giftia.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.giftia.web.ApiException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ConversationServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private ConversationService service;

    @BeforeEach
    void setup() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("conversations.db"));
        jdbc = new JdbcTemplate(dataSource);
        service = new ConversationService(jdbc, mapper);
    }

    @Test
    void readsExistingPythonJsonWithoutChangingMessagesOrCurrentSelection() throws Exception {
        String pythonJson = """
                {"current_id":"first","conversations":{
                  "first":{"title":"第一段对话","messages":[{"role":"user","content":"喜欢书店","image":"data:image/png;base64,abc"},{"role":"assistant","content":"我记住了"}],"created":"11/10 12:00"},
                  "second":{"title":"第二段对话","messages":[],"created":"11/11 13:00"}
                }}
                """;
        jdbc.update("INSERT INTO conversations (user_id,data) VALUES (?,?)", "alice", pythonJson);
        assertThat(service.get("alice", "first").path("messages").get(0).path("image").asText())
                .isEqualTo("data:image/png;base64,abc");
        service.get("alice", "second");
        ObjectNode summary = service.list("alice");
        assertThat(summary.path("current_id").asText()).isEqualTo("first");
        assertThat(summary.path("conversations").get(0).path("id").asText()).isEqualTo("second");
        assertThat(summary.path("conversations").get(1).path("message_count").asInt()).isEqualTo(2);
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT data FROM conversations WHERE user_id='alice'", String.class)))
                .isEqualTo(mapper.readTree(pythonJson));
    }

    @Test
    void commitsWholeExchangeAndPreservesItAcrossServiceRestart() {
        String id = service.list("alice").path("current_id").asText();
        assertThat(service.get("alice", id).path("messages")).isEmpty();
        assertThat(service.appendExchange("alice", id, "今天喜欢喝茶", "我在听", null)
                .path("conversation_id").asText()).isEqualTo(id);
        ConversationService reloaded = new ConversationService(jdbc, mapper);
        assertThat(reloaded.get("alice", id).path("messages")).hasSize(2);
        assertThat(reloaded.get("alice", id).path("title").asText()).isEqualTo("今天喜欢喝茶");
        assertThat(reloaded.list("bob").path("conversations").get(0).path("message_count").asInt()).isZero();
        assertThat(reloaded.allUserIds()).containsExactly("alice", "bob");
    }

    @Test
    void returnedConversationCannotModifyPersistedState() {
        String id = service.list("alice").path("current_id").asText();
        service.appendExchange("alice", id, "今天散步了", "慢慢走", null);
        ObjectNode exposed = service.get("alice", id);
        exposed.put("title", "意外修改");
        exposed.putArray("messages");
        ObjectNode stored = service.get("alice", id);
        assertThat(stored.path("title").asText()).isEqualTo("今天散步了");
        assertThat(stored.path("messages")).hasSize(2);
    }

    @Test
    void conversationUpdatesPreserveUnknownExistingMetadata() throws Exception {
        String original = """
                {"current_id":"kept","future_metadata":{"source":"old backend"},"conversations":{
                  "kept":{"title":"已有记录","messages":[],"created":"11/10 12:00","future_item":{"value":3}}
                }}
                """;
        jdbc.update("INSERT INTO conversations (user_id,data) VALUES (?,?)", "alice", original);
        service.appendExchange("alice", "kept", "今天去散步", "慢慢走", null);
        service.rename("alice", "kept", "散步");
        String created = service.create("alice").path("id").asText();
        service.delete("alice", created);
        var stored = mapper.readTree(jdbc.queryForObject("SELECT data FROM conversations WHERE user_id='alice'", String.class));
        assertThat(stored.path("future_metadata")).isEqualTo(mapper.readTree(original).path("future_metadata"));
        assertThat(stored.path("conversations").path("kept").path("future_item").path("value").asInt()).isEqualTo(3);
        assertThat(stored.path("conversations").path("kept").path("messages")).hasSize(2);
    }

    @Test
    void createsRenamesAndDeletesConversationsWithAReplacementForTheLastOne() {
        String original = service.list("alice").path("current_id").asText();
        String next = service.create("alice").path("id").asText();
        assertThat(next).isNotEqualTo(original);
        assertThat(service.list("alice").path("current_id").asText()).isEqualTo(next);
        assertThat(service.rename("alice", next, "  本周计划  ").path("title").asText()).isEqualTo("本周计划");
        assertThat(service.delete("alice", next).path("current_id").asText()).isEqualTo(original);
        String replacement = service.delete("alice", original).path("current_id").asText();
        assertThat(replacement).isNotEqualTo(original);
        assertThat(service.list("alice").path("conversations")).hasSize(1);
        assertThat(service.get("alice", replacement).path("messages")).isEmpty();
    }

    @Test
    void titleAndConversationLookupsAreValidated() {
        String id = service.list("alice").path("current_id").asText();
        assertThatThrownBy(() -> service.get("alice", "missing")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.rename("alice", id, " ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.rename("alice", id, "长".repeat(101))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.delete("alice", "missing")).isInstanceOf(ApiException.class);
        assertThat(service.get("alice", id).path("title").asText()).isEqualTo("新对话");
    }

    @Test
    void firstTitleUsesUnicodeCodePointsLikePython() {
        String id = service.list("alice").path("current_id").asText();
        String message = "🌱".repeat(25);
        service.appendExchange("alice", id, message, "收到", null);
        assertThat(service.get("alice", id).path("title").asText()).isEqualTo("🌱".repeat(20));
    }

    @Test
    void repairsMissingCurrentIdWithoutDroppingExistingConversation() {
        jdbc.update("INSERT INTO conversations (user_id,data) VALUES (?,?)", "alice", """
                {"current_id":"deleted","conversations":{"kept":{"title":"已有记录","messages":[],"created":"11/10 12:00"}}}
                """);
        assertThat(service.list("alice").path("current_id").asText()).isEqualTo("kept");
        assertThat(service.list("alice").path("conversations")).hasSize(1);
    }

    @Test
    void corruptSavedConversationIsNeverReplacedWithAnEmptyOne() {
        for (String corrupted : List.of("{broken", "null", "{\"conversations\":null}",
                "{\"current_id\":\"bad\",\"conversations\":{\"bad\":42}}",
                "{\"current_id\":\"bad\",\"conversations\":{\"bad\":{\"messages\":{\"keep\":\"unrecognized historical data\"}}}}")) {
            jdbc.update("INSERT OR REPLACE INTO conversations (user_id,data) VALUES (?,?)", "alice", corrupted);
            assertThatThrownBy(() -> service.list("alice"))
                    .isInstanceOf(ApiException.class).hasMessageContaining("原记录已保留");
            assertThatThrownBy(() -> service.appendExchange("alice", null, "新消息", "回复", null))
                    .isInstanceOf(ApiException.class);
            assertThat(jdbc.queryForObject("SELECT data FROM conversations WHERE user_id=?", String.class, "alice"))
                    .isEqualTo(corrupted);
        }
    }

    @Test
    void concurrentCompletedRepliesDoNotOverwriteOtherExchanges() throws Exception {
        String id = service.list("alice").path("current_id").asText();
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> writes = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                int message = index;
                writes.add(() -> {
                    service.appendExchange("alice", id, "消息" + message, "回复" + message, null);
                    return null;
                });
            }
            for (var result : executor.invokeAll(writes)) result.get();
        } finally {
            executor.shutdownNow();
        }
        assertThat(service.get("alice", id).path("messages")).hasSize(40);
    }
}
