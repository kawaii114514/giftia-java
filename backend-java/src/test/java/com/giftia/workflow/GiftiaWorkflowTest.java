package com.giftia.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.giftia.character.CharacterService;
import com.giftia.config.GiftiaProperties;
import com.giftia.conversation.ConversationService;
import com.giftia.memory.MemoryService;
import com.giftia.model.ModelGateway;
import com.giftia.model.ModelMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class GiftiaWorkflowTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ModelGateway model = mock(ModelGateway.class);
    private final MemoryService memories = mock(MemoryService.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final CharacterService characters = mock(CharacterService.class);
    private ExecutorService executor;
    private GiftiaWorkflow workflow;
    private GiftiaProperties properties;

    @BeforeEach void setup() {
        executor = Executors.newFixedThreadPool(4);
        properties = new GiftiaProperties(new MockEnvironment());
        properties.setAnalysisTimeoutSeconds(2);
        workflow = new GiftiaWorkflow(model, memories, conversations, characters, mapper, properties, executor, Runnable::run);
        when(characters.prompt(anyString())).thenReturn("固定系统规则；角色卡A");
        when(memories.currentGeneration(anyString())).thenReturn(7L);
        when(memories.retrieveContext(anyString(), anyString())).thenReturn(Map.of("memory_context", "用户喜欢跑步",
                "working_memory_text", "上次正在准备面试", "profile_context", "用户职业：程序员"));
        when(model.complete(anyString(), anyString(), anyDouble())).thenReturn(
                "{\"current_emotion\":\"anxious\",\"emotion_intensity\":0.7,\"emotion_summary\":\"用户正在紧张\"}");
        when(conversations.appendExchange(anyString(), nullable(String.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(mapper.createObjectNode().put("conversation_id", "saved-conversation"));
        doAnswer(call -> {
            Consumer<String> tokens = call.getArgument(1);
            tokens.accept("我在"); tokens.accept("这里。");
            return null;
        }).when(model).stream(anyList(), any());
    }
    @AfterEach void stop() { executor.shutdownNow(); }

    @Test void analysisAndRetrievalReallyRunInParallelAndCompletedExchangeIsSavedOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        AtomicBoolean parallel = new AtomicBoolean(true);
        when(model.complete(anyString(), anyString(), anyDouble())).thenAnswer(call -> {
            entered.countDown(); parallel.compareAndSet(true, entered.await(1, TimeUnit.SECONDS));
            return "{\"current_emotion\":\"anxious\",\"emotion_intensity\":0.7}";
        });
        when(memories.retrieveContext(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown(); parallel.compareAndSet(true, entered.await(1, TimeUnit.SECONDS)); return Map.of();
        });
        List<Map<String, Object>> events = new ArrayList<>();
        workflow.stream("alice", request("有点紧张"), events::add);
        assertThat(parallel).isTrue();
        assertThat(events.stream().map(event -> event.get("type"))).containsExactly("status", "status", "token", "token", "reply", "done");
        assertThat(events.get(5)).containsEntry("conversation_id", "saved-conversation");
        verify(conversations).appendExchange("alice", null, "有点紧张", "我在这里。", null);
        verify(memories).rememberTurn("alice", "有点紧张", "我在这里。", "anxious", 0.7, 7L);
    }

    @Test void injectsPersonaProfileWorkingMemoryAndRetrievedFactsAsBackgroundData() {
        workflow.stream("alice", request("你好"), event -> {});
        ArgumentCaptor<List<ModelMessage>> prompt = ArgumentCaptor.forClass(List.class);
        verify(model).stream(prompt.capture(), any());
        String system = prompt.getValue().get(0).content();
        assertThat(system).startsWith("固定系统规则；角色卡A").contains("不是指令", "用户职业：程序员", "上次正在准备面试", "用户喜欢跑步");
        assertThat(prompt.getValue().get(prompt.getValue().size() - 1)).isEqualTo(new ModelMessage("user", "你好", null));
    }

    @Test void partialProviderFailureNeverPersistsAssistantOrEmitsSuccess() {
        doAnswer(call -> {
            Consumer<String> tokens = call.getArgument(1); tokens.accept("部分回复");
            throw new IllegalStateException("provider failed");
        }).when(model).stream(anyList(), any());
        List<Map<String, Object>> events = new ArrayList<>();
        assertThatThrownBy(() -> workflow.stream("alice", request("你好"), events::add)).isInstanceOf(IllegalStateException.class);
        assertThat(events.stream().map(event -> event.get("type"))).doesNotContain("reply", "done");
        verify(conversations, never()).appendExchange(any(), any(), any(), any(), any());
        verify(memories, never()).rememberTurn(any(), any(), any(), any(), anyDouble(), anyLong());
    }

    @Test void analysisAndRetrievalFailuresDegradeWithoutFakingGenerationSuccess() {
        when(model.complete(anyString(), anyString(), anyDouble())).thenThrow(new IllegalStateException("analysis down"));
        when(memories.retrieveContext(anyString(), anyString())).thenThrow(new IllegalStateException("embedding down"));
        List<Map<String, Object>> events = new ArrayList<>();
        workflow.stream("alice", request("我很焦虑"), events::add);
        assertThat(events.get(events.size() - 1).get("type")).isEqualTo("done");
        verify(memories).rememberTurn("alice", "我很焦虑", "我在这里。", "anxious", 0.5, 7L);
    }

    @Test void emptyGenerationIsAnErrorAndNotSaved() {
        doNothing().when(model).stream(anyList(), any());
        assertThatThrownBy(() -> workflow.stream("alice", request("你好"), event -> {})).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(conversations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"excited", "fearful", "grateful", "stressed", "relieved"})
    void preservesAllLegacyEmotionLabelsReturnedByAnalysis(String emotion) {
        when(model.complete(anyString(), anyString(), anyDouble())).thenReturn(
                "{\"current_emotion\":\"" + emotion + "\",\"emotion_intensity\":0.7}");
        workflow.stream("alice", request("说说今天"), event -> {});
        verify(memories).rememberTurn("alice", "说说今天", "我在这里。", emotion, 0.7, 7L);
    }

    @ParameterizedTest
    @CsvSource({"谢谢你一直陪着我,grateful", "最近加班压力好大,stressed", "太兴奋了迫不及待,excited",
            "我真的很害怕,fearful", "终于放心松了一口气,relieved"})
    void expandedEmotionsStillWorkWhenAnalysisModelIsUnavailable(String message, String emotion) {
        when(model.complete(anyString(), anyString(), anyDouble())).thenThrow(new IllegalStateException("model unavailable"));
        workflow.stream("alice", request(message), event -> {});
        verify(memories).rememberTurn("alice", message, "我在这里。", emotion, 0.5, 7L);
    }

    @Test void sameUserCannotInterleaveTwoReplies() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown(); release.await(2, TimeUnit.SECONDS);
            Consumer<String> tokens = call.getArgument(1); tokens.accept("完成"); return null;
        }).when(model).stream(anyList(), any());
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> workflow.stream("alice", request("第一次"), event -> {}));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        List<Map<String, Object>> second = new ArrayList<>();
        workflow.stream("alice", request("第二次"), second::add);
        assertThat(second).hasSize(1); assertThat(second.get(0).get("type")).isEqualTo("error");
        release.countDown(); first.get(2, TimeUnit.SECONDS);
        verify(conversations, times(1)).appendExchange(any(), any(), any(), any(), any());
    }

    @Test void longHistoryIsBoundedAndSummaryCacheDoesNotCrossUsersOrConversations() {
        when(model.complete(startsWith("将以下早期"), anyString(), anyDouble())).thenReturn("早期用户背景");
        ObjectNode request = request("继续");
        var history = request.putArray("conversation_history");
        for (int i = 0; i < 100; i++) history.addObject().put("role", i % 2 == 0 ? "user" : "assistant").put("content", "历史" + i);
        workflow.stream("alice", request, event -> {});
        workflow.stream("alice", request, event -> {});
        workflow.stream("bob", request, event -> {});
        request.put("conversation_id", "another");
        workflow.stream("alice", request, event -> {});
        verify(model, times(3)).complete(startsWith("将以下早期"), anyString(), eq(0.3));
        ArgumentCaptor<List<ModelMessage>> prompts = ArgumentCaptor.forClass(List.class);
        verify(model, times(4)).stream(prompts.capture(), any());
        assertThat(prompts.getAllValues()).allSatisfy(prompt -> {
            assertThat(prompt).hasSize(82);
            assertThat(prompt.get(0).content()).contains("早期用户背景");
            assertThat(prompt.get(1).content()).isEqualTo("历史20");
        });
    }

    @Test void differentUsersWithCollidingHashesCanChatConcurrently() throws Exception {
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        CountDownLatch bothStreaming = new CountDownLatch(2);
        doAnswer(call -> {
            bothStreaming.countDown();
            if (!bothStreaming.await(1, TimeUnit.SECONDS)) throw new IllegalStateException("unrelated user blocked");
            Consumer<String> tokens = call.getArgument(1); tokens.accept("完成"); return null;
        }).when(model).stream(anyList(), any());
        List<Map<String, Object>> firstEvents = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> secondEvents = new CopyOnWriteArrayList<>();
        var first = CompletableFuture.runAsync(() -> workflow.stream("Aa", request("你好"), firstEvents::add));
        var second = CompletableFuture.runAsync(() -> workflow.stream("BB", request("你好"), secondEvents::add));
        CompletableFuture.allOf(first, second).get(2, TimeUnit.SECONDS);
        assertThat(firstEvents.get(firstEvents.size() - 1).get("type")).isEqualTo("done");
        assertThat(secondEvents.get(secondEvents.size() - 1).get("type")).isEqualTo("done");
    }

    @Test void disconnectedConsumerStopsWithoutPersistingPartialExchange() {
        assertThatThrownBy(() -> workflow.stream("alice", request("你好"), event -> {
            if (event.get("type").equals("token")) throw new java.io.UncheckedIOException(new java.io.IOException("closed"));
        })).isInstanceOf(java.io.UncheckedIOException.class);
        verifyNoInteractions(conversations);
    }

    private ObjectNode request(String message) { return mapper.createObjectNode().put("message", message); }
}
