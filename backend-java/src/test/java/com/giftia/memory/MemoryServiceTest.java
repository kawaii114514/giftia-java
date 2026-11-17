package com.giftia.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.giftia.model.ModelGateway;
import com.giftia.model.ModelMessage;
import com.giftia.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryServiceTest {
    @TempDir Path directory;
    private static final double NOW = Instant.parse("2026-11-13T04:00:00Z").toEpochMilli() / 1000.0;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-13T04:00:00Z"), ZoneOffset.UTC);
    private JdbcTemplate jdbc;
    private FakeModels models;
    private MemoryService service;

    @BeforeEach
    void setup() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("memory-test.db"));
        jdbc = new JdbcTemplate(dataSource);
        models = new FakeModels();
        service = new MemoryService(jdbc, mapper, models, clock);
    }

    @AfterEach
    void shutdown() { service.close(); }

    @Test
    void readsPythonRowsAndPreservesFractionalTimestampsAndUnknownFieldsWhenChangingLayer() throws Exception {
        String python = """
                {"id":"old","content":"用户喜欢图书馆","emotion":"happy","category":"preference",
                 "importance":0.734,"created_at":1730000000.125,"last_accessed":0.0,
                 "access_count":2,"tags":["喜欢"],"is_consolidated":true,"future_metadata":{"source":"python"}}
                """;
        jdbc.update("INSERT INTO memories(user_id,memory_id,data) VALUES(?,?,?)", "alice", "old", python);
        JsonNode detail = service.detail("alice").path("layers").path("regular").get(0);
        assertThat(detail.path("created_at").asDouble()).isEqualTo(1730000000.125);
        assertThat(detail.path("emotion_emoji").asText()).isEqualTo("😊");
        assertThat(detail.path("temporal_data").isObject()).isTrue();
        assertThat(detail.has("embedding")).isFalse();
        assertThat(service.stats("alice").path("avg_importance").asDouble()).isEqualTo(.734);
        assertThat(service.stats("alice").path("consolidated_count").asInt()).isEqualTo(1);
        assertThat(service.changeLayer("alice", "old", 1).path("new_layer").asInt()).isEqualTo(1);
        JsonNode stored = row("alice", "old");
        assertThat(stored.path("created_at").asDouble()).isEqualTo(1730000000.125);
        assertThat(stored.path("future_metadata").path("source").asText()).isEqualTo("python");
        assertThat(service.layers("alice").path("core").path("count").asInt()).isEqualTo(1);
        assertThat(service.stats("bob").path("total").asInt()).isZero();
        assertThat(service.stats("bob").path("emotion_distribution").isObject()).isTrue();
        assertThatThrownBy(() -> service.changeLayer("alice", "old", 0))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.status()).isEqualTo(400));
        assertThatThrownBy(() -> service.changeLayer("bob", "old", 1))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.status()).isEqualTo(404));
    }

    @Test
    void combinesSemanticAndKeywordRanksAcrossRewrittenQueriesAndOnlyTouchesRetrievedRows() throws Exception {
        models.rewrite = "```json\n[\"烘焙爱好\",\"烘焙爱好\"]\n```";
        models.embedding = texts -> texts.stream().map(ignored -> List.of(1.0, 0.0)).toList();
        insert("alice", "both", "用户有烘焙爱好", List.of(1.0, 0.0));
        insert("alice", "semantic", "用户养了小猫", List.of(.9, .1));
        insert("alice", "keyword", "我有什么爱好", List.of(0.0, 1.0));
        insert("alice", "unrelated", "完全无关的内容", List.of(0.0, 1.0));
        insert("bob", "other-user", "用户有烘焙爱好", List.of(1.0, 0.0));

        Map<String, Object> context = service.retrieveContext("alice", "我有什么爱好");
        List<Map<String, Object>> retrieved = retrieved(context);
        assertThat(retrieved).extracting(row -> row.get("id")).containsExactly("both", "keyword", "semantic");
        assertThat(retrieved.get(0)).containsEntry("memory", "用户有烘焙爱好").containsEntry("source", "local");
        assertThat(retrieved.get(0)).doesNotContainKey("embedding");
        assertThat(context.get("memory_context").toString()).contains("用户有烘焙爱好", "用户养了小猫");
        for (String id : List.of("both", "semantic", "keyword")) {
            assertThat(row("alice", id).path("access_count").asInt()).isEqualTo(1);
            assertThat(row("alice", id).path("last_accessed").asDouble()).isEqualTo(NOW);
        }
        assertThat(row("alice", "unrelated").path("access_count").asInt()).isZero();
        assertThat(row("bob", "other-user").path("access_count").asInt()).isZero();
    }

    @Test
    void backfillsOldVectorsInBatchesAndPersistsTheResults() throws Exception {
        models.embedding = texts -> texts.stream().map(ignored -> List.of(1.0, .5)).toList();
        insert("alice", "missing", "用户喜欢户外徒步", null);
        insert("alice", "old-model", "用户喜欢看星星", List.of(1.0, 2.0, 3.0));
        assertThat(retrieved(service.retrieveContext("alice", "兴趣"))).hasSize(2);
        assertThat(row("alice", "missing").path("embedding")).hasSize(2);
        assertThat(row("alice", "old-model").path("embedding")).hasSize(2);
        service.close();
        service = new MemoryService(jdbc, mapper, models, clock);
        assertThat(retrieved(service.retrieveContext("alice", "兴趣"))).hasSize(2);
        assertThat(row("alice", "missing").path("access_count").asInt()).isEqualTo(2);
    }

    @Test
    void embeddingAndInvalidModelJsonFailuresRetainChineseKeywordRetrieval() {
        models.rewrite = "not json";
        models.embedding = texts -> { throw new IllegalStateException("provider unavailable"); };
        insert("alice", "match", "用户喜欢在周末逛书店", null);
        insert("alice", "miss", "用户养了一只小猫", null);
        List<Map<String, Object>> results = retrieved(service.retrieveContext("alice", "周末逛书店"));
        assertThat(results).extracting(result -> result.get("id")).containsExactly("match");
    }

    @Test
    void storesFactsLayersTemporalDataWorkingMemoryAndProfileWithoutConfusingAiRecommendations() throws Exception {
        models.facts = "[\"AI曾向用户推荐了歌曲《晴天》\"]";
        models.working = """
                {"summary":"小林喜欢爬山，希望周末出游","open_topics":["周末路线"],"current_emotion":"happy"}
                """;
        models.profile = """
                {"preferences.hobbies":["爬山"],"relationships.romantic.status":"单身"}
                """;
        service.rememberTurn("alice", "我叫小林，我今年25岁，我喜欢爬山，我单身", "AI曾向你推荐歌曲《晴天》", "happy", .9);
        JsonNode details = service.detail("alice");
        JsonNode memories = details.path("layers").path("core");
        assertThat(memories).hasSize(4);
        List<String> contents = new ArrayList<>();
        memories.forEach(memory -> contents.add(memory.path("content").asText()));
        assertThat(contents).contains("[关于用户] 我叫小林", "[关于用户] 我喜欢爬山", "[关于用户] 我单身",
                "[AI互动] AI曾向用户推荐了歌曲《晴天》");
        assertThat(contents).noneMatch(value -> value.equals("[关于用户] AI曾向用户推荐了歌曲《晴天》"));
        assertThat(details.path("working_memory").path("summary").asText()).isEqualTo("小林喜欢爬山，希望周末出游");
        JsonNode profile = service.profile("alice");
        assertThat(profile.path("profile").path("identity").path("name").asText()).isEqualTo("小林");
        assertThat(profile.path("profile").path("identity").path("age").asInt()).isEqualTo(25);
        assertThat(profile.path("profile").path("relationships").path("romantic").path("status").asText()).isEqualTo("单身");
        assertThat(profile.path("version").asInt()).isEqualTo(2);
        assertThat(profile.path("prompt_context").asText()).contains("小林", "25岁", "喜欢爬山");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM user_profiles WHERE user_id='alice'", Double.class)).isEqualTo(NOW);
        assertThat(service.allUserIds()).containsExactly("alice");
    }

    @Test
    void persistsFallbackContextAcrossServiceInstancesAndInjectsProfileOnEveryRetrieval() {
        models.facts = "[]";
        models.working = "invalid JSON";
        models.profile = "invalid JSON";
        service.rememberTurn("alice", "我叫小雨，我今年22岁，我最近特别焦虑", "我会陪你慢慢梳理。", "anxious", .7);
        assertThat(service.detail("alice").path("layers").path("important")).isNotEmpty();
        assertThat(service.profile("alice").path("profile").path("identity").path("name").asText()).isEqualTo("小雨");
        service.close();
        service = new MemoryService(jdbc, mapper, models, clock);
        Map<String, Object> context = service.retrieveContext("alice", "最近焦虑");
        assertThat(context.get("working_memory_text").toString()).contains("最近提到", "焦虑", "anxious");
        assertThat(context.get("profile_context").toString()).contains("小雨", "22岁");
        assertThat(retrieved(context)).isNotEmpty();
        assertThat(service.detail("bob").path("working_memory").path("summary").asText()).isEmpty();
    }

    @Test
    void storesAUserAttributedDialogueSummaryWhenThereAreNoExtractableFacts() {
        service.rememberTurn("alice", "晚安啦", "祝你做个好梦", "neutral", 0);
        JsonNode memories = service.detail("alice").path("layers").path("regular");
        assertThat(memories).hasSize(2);
        assertThat(memories.get(0).path("content").asText()).startsWith("[对话摘要] 用户说：");
        assertThat(memories.get(1).path("content").asText()).startsWith("[对话摘要] AI回复要点：");
    }

    @Test
    void anIncorrectModelTagCannotConvertAnAiRecommendationIntoAUserFact() {
        models.facts = "[\"[关于用户] AI曾建议用户尝试冥想\",\"[AI互动] 推荐用户阅读《活着》\"]";
        service.rememberTurn("alice", "今天还行吧", "你可以尝试冥想，也可以阅读《活着》。", "neutral", 0);
        JsonNode memories = service.detail("alice").path("layers").path("regular");
        assertThat(memories).hasSize(2);
        for (JsonNode memory : memories) assertThat(memory.path("content").asText()).startsWith("[AI互动] ");
    }

    @Test
    void corruptHistoricMemoryRowsDoNotHideHealthyRowsOrGetOverwritten() {
        jdbc.update("INSERT INTO memories(user_id,memory_id,data) VALUES(?,?,?)", "alice", "corrupt", "");
        String malformedContent = "{\"content\":{\"unrecognized\":\"original content\"},\"embedding\":[1,0]}";
        jdbc.update("INSERT INTO memories(user_id,memory_id,data) VALUES(?,?,?)", "alice", "bad-content", malformedContent);
        insert("alice", "healthy", "用户喜欢书店", null);
        assertThat(service.stats("alice").path("total").asInt()).isEqualTo(1);
        assertThat(retrieved(service.retrieveContext("alice", "书店"))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT data FROM memories WHERE memory_id='corrupt'", String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT data FROM memories WHERE memory_id='bad-content'", String.class))
                .isEqualTo(malformedContent);
    }

    @Test
    void corruptStoredWorkingMemoryIsNeverSilentlyReplacedDuringANewTurn() {
        for (String topics : List.of("{broken", "null", "{\"unrecognized\":\"topic\"}", "[42]")) {
            jdbc.update("INSERT OR REPLACE INTO working_memory(user_id,summary,open_topics,current_emotion,updated_at) VALUES(?,?,?,?,?)",
                    "alice", "旧摘要必须保留", topics, "happy", NOW);
            assertThatThrownBy(() -> service.rememberTurn("alice", "我喜欢书店", "听见了", "neutral", .1))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT open_topics FROM working_memory WHERE user_id='alice'", String.class)).isEqualTo(topics);
            assertThat(jdbc.queryForObject("SELECT summary FROM working_memory WHERE user_id='alice'", String.class)).isEqualTo("旧摘要必须保留");
            assertThat(service.stats("alice").path("total").asInt()).isZero();
        }
    }

    @Test
    void corruptStoredProfileSectionsAreNeverSilentlyReplacedByManualOrAutomaticUpdates() throws Exception {
        for (String stored : List.of("{broken", "null", "{\"identity\":null}",
                "{\"identity\":[\"unrecognized historical values\"]}")) {
            jdbc.update("INSERT OR REPLACE INTO user_profiles(user_id,profile_data,created_at,updated_at,version) VALUES(?,?,?,?,?)",
                    "alice", stored, NOW, NOW, 4);
            assertThatThrownBy(() -> service.updateProfile("alice", mapper.readTree("{\"identity\":{\"name\":\"新名字\"}}")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.rememberTurn("alice", "我叫新名字", "听见了", "neutral", .1))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT profile_data FROM user_profiles WHERE user_id='alice'", String.class)).isEqualTo(stored);
            assertThat(jdbc.queryForObject("SELECT version FROM user_profiles WHERE user_id='alice'", Integer.class)).isEqualTo(4);
            assertThat(service.stats("alice").path("total").asInt()).isZero();
        }
    }

    @Test
    void replacesProfileArraysAndOnlyBumpsAutomaticVersionWhenFieldsChange() throws Exception {
        service.updateProfile("alice", mapper.readTree("""
                {"identity":{"name":"小雨"},"preferences":{"hobbies":["阅读","爬山"]}}
                """));
        models.profile = "{\"preferences.hobbies\":[\"音乐\"],\"relationships.romantic\":{\"status\":\"单身\"}}";
        service.rememberTurn("alice", "我喜欢音乐，我单身", "听起来不错", "neutral", 0);
        JsonNode updated = service.profile("alice");
        assertThat(updated.path("version").asInt()).isEqualTo(3);
        assertThat(updated.path("profile").path("preferences").path("hobbies")).hasSize(1);
        assertThat(updated.path("profile").path("preferences").path("hobbies").get(0).asText()).isEqualTo("音乐");
        service.rememberTurn("alice", "我喜欢音乐，我单身", "知道啦", "neutral", 0);
        assertThat(service.profile("alice").path("version").asInt()).isEqualTo(3);
        assertThat(service.profile("alice").path("profile").path("identity").path("name").asText()).isEqualTo("小雨");
        assertThatThrownBy(() -> service.updateProfile("alice", mapper.getNodeFactory().textNode("bad"))).isInstanceOf(ApiException.class);
        assertThat(service.profile("alice").path("version").asInt()).isEqualTo(3);
    }

    @Test
    void clearErasesAllMemorySurfacesAtomicallyWhilePreservingConversationsAndOtherUsers() throws Exception {
        jdbc.execute("CREATE TABLE conversations(user_id TEXT PRIMARY KEY,data TEXT NOT NULL)");
        jdbc.update("INSERT INTO conversations(user_id,data) VALUES(?,?)", "alice", "{\"messages\":[\"keep\"]}");
        service.rememberTurn("alice", "我叫小明，我喜欢咖啡", "记住啦", "neutral", .3);
        service.rememberTurn("bob", "我叫小刚，我喜欢茶", "记住啦", "neutral", .3);
        service.clear("alice");
        service.close();
        service = new MemoryService(jdbc, mapper, models, clock);
        assertThat(service.stats("alice").path("total").asInt()).isZero();
        assertThat(service.detail("alice").path("working_memory").path("summary").asText()).isEmpty();
        assertThat(service.profile("alice").path("profile").path("identity")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversations WHERE user_id='alice'", Integer.class)).isEqualTo(1);
        assertThat(service.stats("bob").path("total").asInt()).isGreaterThan(0);
        assertThat(service.allUserIds()).containsExactly("bob");
        assertThat(retrieved(service.retrieveContext("alice", "我叫什么"))).isEmpty();
    }

    @Test
    void clearDoesNotWaitForAnInflightModelAndItsResultCannotResurrectMemory() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        models.beforeComplete = system -> {
            if (system.contains("长期记忆事实提取")) {
                entered.countDown();
                await(release);
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var save = executor.submit(() -> service.rememberTurn("alice", "我叫小林，我喜欢爬山", "记住啦", "happy", .9));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var clear = executor.submit(() -> service.clear("alice"));
            assertThat(clear.get(1, TimeUnit.SECONDS).path("ok").asBoolean()).isTrue();
            release.countDown();
            save.get(3, TimeUnit.SECONDS);
            assertThat(service.stats("alice").path("total").asInt()).isZero();
            assertThat(service.detail("alice").path("working_memory").path("summary").asText()).isEmpty();
            assertThat(service.profile("alice").path("profile").path("identity")).isEmpty();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void clearingAnInflightRetrievalDiscardsOldContextAndPendingVectorBackfills() throws Exception {
        insert("alice", "old", "用户喜欢书店", null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        models.beforeComplete = system -> {
            if (system.contains("记忆整理模块")) {
                entered.countDown();
                await(release);
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var retrieval = executor.submit(() -> service.retrieveContext("alice", "书店"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> service.clear("alice")).get(1, TimeUnit.SECONDS);
            release.countDown();
            Map<String, Object> result = retrieval.get(3, TimeUnit.SECONDS);
            assertThat(retrieved(result)).isEmpty();
            assertThat(result.get("memory_context").toString()).doesNotContain("书店");
            assertThat(service.stats("alice").path("total").asInt()).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aQueuedOldGenerationIsDiscardedBeforeCallingAnyModel() {
        long queuedGeneration = service.currentGeneration("alice");
        service.clear("alice");
        AtomicInteger calls = new AtomicInteger();
        models.beforeComplete = system -> calls.incrementAndGet();
        service.rememberTurn("alice", "我叫旧名字", "记住啦", "neutral", 0, queuedGeneration);
        assertThat(calls).hasValue(0);
        assertThat(service.stats("alice").path("total").asInt()).isZero();
        service.rememberTurn("alice", "我叫新名字", "记住啦", "neutral", 0);
        assertThat(service.profile("alice").path("profile").path("identity").path("name").asText()).isEqualTo("新名字");
    }

    @Test
    void concurrentProfileExtractionsMergeIntoLatestStateAndPreserveWorkingFacts() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        models.beforeComplete = system -> {
            if (system.contains("长期记忆事实提取")) {
                entered.countDown();
                await(release);
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var name = executor.submit(() -> service.rememberTurn("alice", "我叫小松", "记住啦", "neutral", 0));
            var age = executor.submit(() -> service.rememberTurn("alice", "我今年25岁", "记住啦", "neutral", 0));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            name.get(3, TimeUnit.SECONDS);
            age.get(3, TimeUnit.SECONDS);
            JsonNode identity = service.profile("alice").path("profile").path("identity");
            assertThat(identity.path("name").asText()).isEqualTo("小松");
            assertThat(identity.path("age").asInt()).isEqualTo(25);
            assertThat(service.detail("alice").path("working_memory").path("summary").asText()).contains("小松", "25岁");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void retrievalAccessUpdatesPreserveLayerChangesMadeWhileAModelIsRunning() throws Exception {
        insert("alice", "memory", "用户喜欢书店", null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        models.beforeComplete = system -> {
            if (system.contains("记忆整理模块")) {
                entered.countDown();
                await(release);
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var retrieval = executor.submit(() -> service.retrieveContext("alice", "书店"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> service.changeLayer("alice", "memory", 1)).get(1, TimeUnit.SECONDS);
            release.countDown();
            assertThat(retrieved(retrieval.get(3, TimeUnit.SECONDS)).get(0)).containsEntry("layer", 1);
            assertThat(row("alice", "memory").path("layer").asInt()).isEqualTo(1);
            assertThat(row("alice", "memory").path("access_count").asInt()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentUpdatesForTheSameUserDoNotLoseFacts() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                int number = index;
                tasks.add(() -> {
                    service.rememberTurn("alice", "我喜欢第" + number + "号歌曲", "记住啦", "neutral", 0);
                    return null;
                });
            }
            for (var task : executor.invokeAll(tasks)) task.get();
        } finally { executor.shutdownNow(); }
        assertThat(service.stats("alice").path("total").asInt()).isEqualTo(12);
    }

    @Test
    @Timeout(8)
    void queryRewriteHasARealBoundedTimeout() {
        insert("alice", "match", "用户喜欢阅读小说", null);
        models.blockRewrite = true;
        long start = System.nanoTime();
        assertThat(retrieved(service.retrieveContext("alice", "阅读小说"))).hasSize(1);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(7000);
    }

    @Test
    void attachesCompoundDatesLifeStagesAndRecurrenceToNewMemories() {
        service.rememberTurn("alice", "我在去年夏天大学时期每天跑步", "继续保持呀", "neutral", .2);
        JsonNode metadata = service.detail("alice").path("layers").path("regular").get(0).path("temporal_data");
        assertThat(metadata.path("event_time").path("description").asText()).isEqualTo("去年夏天");
        assertThat(metadata.path("event_time").path("timestamp").asDouble())
                .isEqualTo((double) Instant.parse("2025-05-31T16:00:00Z").getEpochSecond());
        assertThat(metadata.path("time_context").path("life_stage").asText()).isEqualTo("大学时期");
        assertThat(metadata.path("recurrence").path("frequency").asText()).isEqualTo("daily");
    }

    private void insert(String user, String id, String content, List<Double> vector) {
        ObjectNode value = mapper.createObjectNode().put("id", id).put("content", content).put("emotion", "neutral")
                .put("category", "fact").put("importance", .5).put("created_at", NOW).put("last_accessed", 0)
                .put("access_count", 0).put("layer", 3);
        if (vector != null) value.set("embedding", mapper.valueToTree(vector));
        jdbc.update("INSERT INTO memories(user_id,memory_id,data) VALUES(?,?,?)", user, id, value.toString());
    }

    private JsonNode row(String user, String id) throws Exception {
        return mapper.readTree(jdbc.queryForObject("SELECT data FROM memories WHERE user_id=? AND memory_id=?", String.class, user, id));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("Test model gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> retrieved(Map<String, Object> context) {
        return (List<Map<String, Object>>) context.get("retrieved_memories");
    }

    private static final class FakeModels implements ModelGateway {
        String rewrite = "[]";
        String facts = "[]";
        String working = "{}";
        String profile = "{}";
        boolean blockRewrite;
        Consumer<String> beforeComplete = ignored -> { };
        Function<List<String>, List<List<Double>>> embedding = texts -> texts.stream().map(ignored -> List.of(0.0, 0.0)).toList();

        @Override public String complete(String system, String user, double temperature) {
            beforeComplete.accept(system);
            if (system.contains("检索查询改写")) {
                if (blockRewrite) {
                    try { Thread.sleep(30_000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                return rewrite;
            }
            if (system.contains("长期记忆事实提取")) return facts;
            if (system.contains("跨对话工作记忆更新")) return working;
            if (system.contains("用户档案提取")) return profile;
            return "{}";
        }
        @Override public List<List<Double>> embed(List<String> texts) { return embedding.apply(texts); }
        @Override public void stream(List<ModelMessage> messages, Consumer<String> onToken) { throw new UnsupportedOperationException(); }
        @Override public Map<String, Object> info() { return Map.of(); }
        @Override public Map<String, Object> presets() { return Map.of(); }
        @Override public boolean configured() { return true; }
        @Override public boolean healthy() { return true; }
    }
}
