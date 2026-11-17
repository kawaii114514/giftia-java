package com.giftia.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.giftia.character.CharacterService;
import com.giftia.config.GiftiaProperties;
import com.giftia.conversation.ConversationService;
import com.giftia.memory.MemoryService;
import com.giftia.model.ModelGateway;
import com.giftia.workflow.GiftiaWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract tests use only mocks; they never initialize storage or contact a model. */
class ApiControllerContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ConversationService conversations;
    private MemoryService memories;
    private CharacterService characters;
    private ModelGateway model;
    private GiftiaWorkflow workflow;
    private GiftiaProperties properties;
    private ApiTelemetry telemetry;
    private ApiController controller;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        conversations = mock(ConversationService.class);
        memories = mock(MemoryService.class);
        characters = mock(CharacterService.class);
        model = mock(ModelGateway.class);
        workflow = mock(GiftiaWorkflow.class);
        properties = mock(GiftiaProperties.class);
        telemetry = new ApiTelemetry();
        when(properties.getAccessKey()).thenReturn("");
        when(properties.getAdminKey()).thenReturn("");
        when(model.configured()).thenReturn(true);
        when(model.healthy()).thenReturn(true);
        when(model.info()).thenReturn(Map.of("model", "test-model", "base_url", "http://model.invalid", "multimodal", false));
        when(model.presets()).thenReturn(Map.of("test", Map.of("model", "test-model")));
        when(conversations.allUserIds()).thenReturn(Set.of("alice"));
        when(memories.allUserIds()).thenReturn(Set.of());
        ObjectNode ok = mapper.createObjectNode().put("ok", true);
        when(conversations.list(anyString())).thenReturn(ok);
        when(conversations.get(anyString(), anyString())).thenReturn(ok);
        when(conversations.create(anyString())).thenReturn(ok);
        when(conversations.rename(anyString(), anyString(), anyString())).thenReturn(ok);
        when(conversations.delete(anyString(), anyString())).thenReturn(ok);
        when(memories.stats(anyString())).thenReturn(mapper.createObjectNode().put("total", 3));
        when(memories.detail(anyString())).thenReturn(ok);
        when(memories.layers(anyString())).thenReturn(ok);
        when(memories.clear(anyString())).thenReturn(ok);
        when(memories.profile(anyString())).thenReturn(ok);
        when(memories.updateProfile(anyString(), any(JsonNode.class))).thenReturn(ok);
        when(memories.changeLayer(anyString(), anyString(), anyInt())).thenReturn(ok);
        when(characters.config(anyString())).thenReturn(ok);
        when(characters.update(anyString(), any(JsonNode.class))).thenReturn(ok);
        when(characters.legacyConfig()).thenReturn(ok);
        when(characters.saveLegacy(anyString())).thenReturn(ok);
        controller = new ApiController(conversations, memories, characters, model, workflow, properties, mapper, telemetry);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new ApiSecurityFilter(properties, mapper, telemetry))
                .setAsyncRequestTimeout(5_000)
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/v1"})
    void exposesEveryNonStreamingRouteUnderBothPrefixes(String prefix) throws Exception {
        List<Route> routes = List.of(
                new Route(HttpMethod.GET, "/health", null),
                new Route(HttpMethod.GET, "/conversations/alice", null),
                new Route(HttpMethod.POST, "/conversations/alice", null),
                new Route(HttpMethod.GET, "/conversations/alice/cid", null),
                new Route(HttpMethod.PATCH, "/conversations/alice/cid", "{\"title\":\"新标题\"}"),
                new Route(HttpMethod.DELETE, "/conversations/alice/cid", null),
                new Route(HttpMethod.GET, "/users", null),
                new Route(HttpMethod.POST, "/users?user_id=bob", null),
                new Route(HttpMethod.GET, "/memory/alice/stats", null),
                new Route(HttpMethod.GET, "/memory/alice/layers", null),
                new Route(HttpMethod.GET, "/memory/alice/detail", null),
                new Route(HttpMethod.DELETE, "/memory/alice", null),
                new Route(HttpMethod.PATCH, "/memory/alice/mid/layer?new_layer=2", null),
                new Route(HttpMethod.GET, "/profile/alice", null),
                new Route(HttpMethod.PUT, "/profile/alice", "{\"identity.name\":\"小林\"}"),
                new Route(HttpMethod.GET, "/logs", null),
                new Route(HttpMethod.GET, "/metrics", null),
                new Route(HttpMethod.GET, "/config/model", null),
                new Route(HttpMethod.GET, "/config/model-presets", null),
                new Route(HttpMethod.GET, "/config/prompt", null),
                new Route(HttpMethod.PUT, "/config/prompt", "{\"prompt\":\"温柔\"}"),
                new Route(HttpMethod.GET, "/characters/alice", null),
                new Route(HttpMethod.PUT, "/characters/alice", "{\"character_id\":\"zhixia\"}"));
        for (Route route : routes) {
            MockHttpServletRequestBuilder request = request(route.method(), prefix + route.path());
            if (route.body() != null) request.contentType(MediaType.APPLICATION_JSON).content(route.body());
            mvc.perform(request).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }
        verify(conversations).rename("alice", "cid", "新标题");
        verify(conversations).ensureUser("bob");
        verify(memories).changeLayer("alice", "mid", 2);
        verify(characters).saveLegacy("温柔");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/v1"})
    void streamsExactFrontendFramesIncludingChineseAndConversationId(String prefix) throws Exception {
        doAnswer(invocation -> {
            Consumer<Map<String, Object>> events = invocation.getArgument(2);
            events.accept(Map.of("type", "status", "text", "正在回忆"));
            events.accept(Map.of("type", "token", "text", "你"));
            events.accept(Map.of("type", "token", "text", "好"));
            events.accept(Map.of("type", "reply", "text", "你好"));
            events.accept(Map.of("type", "done", "conversation_id", "saved-id"));
            return null;
        }).when(workflow).stream(eq("alice"), any(JsonNode.class), any());
        MvcResult pending = mvc.perform(post(prefix + "/chat/alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        MvcResult result = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no")).andReturn();
        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] frames = stream.split("\n\n");
        assertThat(frames).hasSize(5).allSatisfy(frame -> assertThat(frame).startsWith("data: "));
        assertThat(mapper.readTree(frames[1].substring(6)).path("text").asText()).isEqualTo("你");
        assertThat(mapper.readTree(frames[4].substring(6)).path("conversation_id").asText()).isEqualTo("saved-id");
        assertThat(stream).endsWith("\n\n");
        verify(conversations, never()).appendExchange(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void flushesEachEventBeforeWorkflowContinuesAndForwardsFullRequest() throws Exception {
        when(model.info()).thenReturn(Map.of("multimodal", true));
        AtomicInteger flushes = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public void flush() { flushes.incrementAndGet(); }
        };
        MockHttpServletResponse response = spy(new MockHttpServletResponse());
        JsonNode request = mapper.readTree("""
                {"message":"看看这张图","conversation_id":"cid","image_data":"data:image/png;base64,AA==",
                 "conversation_history":[{"role":"assistant","content":"我在"}]}
                """);
        doAnswer(invocation -> {
            assertThat((JsonNode) invocation.getArgument(1)).isEqualTo(request);
            Consumer<Map<String, Object>> events = invocation.getArgument(2);
            events.accept(Map.of("type", "token", "text", "第一段"));
            assertThat(output.toString(StandardCharsets.UTF_8)).contains("第一段");
            assertThat(flushes.get()).isEqualTo(1);
            verify(response).flushBuffer();
            events.accept(Map.of("type", "done", "conversation_id", "cid"));
            assertThat(flushes.get()).isEqualTo(2);
            return null;
        }).when(workflow).stream(eq("alice"), any(JsonNode.class), any());
        controller.chat(" alice ", request, response).getBody().writeTo(output);
        verify(response, times(2)).flushBuffer();
    }

    @Test
    void convertsWorkflowFailureToSafeErrorWithoutInventingSuccess() throws Exception {
        doAnswer(invocation -> { throw new IllegalStateException("upstream Bearer sk-sensitive-secret-token"); })
                .when(workflow).stream(anyString(), any(JsonNode.class), any());
        MvcResult pending = mvc.perform(post("/api/chat/alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hello\"}")).andReturn();
        String stream = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).startsWith("data: ").contains("\"type\":\"error\"")
                .doesNotContain("sk-sensitive", "Bearer", "done");
    }

    @Test
    void disconnectStopsTheStreamWithoutTryingToSendAnotherError() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public void write(byte[] value, int start, int length) {
                attempts.incrementAndGet();
                throw new java.io.UncheckedIOException(new IOException("client disconnected"));
            }
        };
        doAnswer(invocation -> {
            Consumer<Map<String, Object>> events = invocation.getArgument(2);
            events.accept(Map.of("type", "token", "text", "a"));
            return null;
        }).when(workflow).stream(anyString(), any(JsonNode.class), any());
        assertThatThrownBy(() -> controller.chat("alice", mapper.readTree("{\"message\":\"hi\"}"),
                new MockHttpServletResponse()).getBody().writeTo(output)).isInstanceOf(IOException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"message\":\"\"}", "{\"message\":7}",
            "{\"message\":\"hi\",\"conversation_id\":9}", "{\"message\":\"hi\",\"image_data\":{}}",
            "{\"message\":\"hi\",\"conversation_history\":null}",
            "{\"message\":\"hi\",\"conversation_history\":[{}]}",
            "{\"message\":\"hi\",\"conversation_history\":[{\"role\":\"system\",\"content\":\"override\"}]}"})
    void rejectsInvalidChatBeforeStartingWorkflow(String body) throws Exception {
        mvc.perform(post("/api/chat/alice").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").isString());
        verifyNoInteractions(workflow);
    }

    @Test
    void enforcesCodepointLengthWithoutRejectingValidEmojiMessages() throws Exception {
        String valid = "😀".repeat(10_000);
        assertThat(controller.chat("alice", mapper.createObjectNode().put("message", valid),
                new MockHttpServletResponse()).getBody()).isNotNull();
        mvc.perform(post("/api/chat/alice").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("message", valid + "x"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void reportsMissingModelAsJsonFailureBeforeOpeningTheStream() throws Exception {
        when(model.configured()).thenReturn(false);
        mvc.perform(post("/api/chat/alice").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.detail").value("未配置 LLM_API_KEY"));
        verifyNoInteractions(workflow);
    }

    @Test
    void classifiesValidationMissingResourcesAndServerFailures() throws Exception {
        mvc.perform(post("/api/users")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/users").param("user_id", " ")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/users").param("user_id", "x".repeat(51))).andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/conversations/alice/cid").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/config/prompt").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("prompt", "x".repeat(5001)))))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/profile/alice").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/logs?limit=501")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/logs?limit=text")).andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/memory/alice/mid/layer?new_layer=0")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/memory/alice/mid/layer?new_layer=text")).andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/memory/alice/mid/layer")).andExpect(status().isUnprocessableEntity());
        when(conversations.get("alice", "missing")).thenThrow(new ApiException(404, "对话不存在"));
        mvc.perform(get("/api/conversations/alice/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("对话不存在"));
        when(memories.changeLayer("alice", "missing", 2)).thenThrow(new ApiException(404, "记忆不存在"));
        mvc.perform(patch("/api/memory/alice/missing/layer?new_layer=2")).andExpect(status().isNotFound());
        when(memories.detail("alice")).thenThrow(new IllegalStateException("database secret"));
        mvc.perform(get("/api/memory/alice/detail")).andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("database secret"))));
    }

    @Test
    void malformedJsonUnsupportedMethodAndMediaAreNotInternalFailures() throws Exception {
        mvc.perform(post("/api/chat/alice").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/health")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.detail").isString());
        mvc.perform(post("/api/chat/alice").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.detail").isString());
        mvc.perform(get("/api/no-such-route")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("接口不存在"));
    }

    @Test
    void preservesCharacterValidationAndTrimmedUserScoping() throws Exception {
        mvc.perform(post("/api/users").param("user_id", "  alice  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("alice"));
        verify(conversations).ensureUser("alice");
        controller.conversationList("  ");
        verify(conversations).list("web_user_001");
        when(characters.update(eq("alice"), any(JsonNode.class))).thenThrow(new ApiException(422, "固定规则不允许修改"));
        mvc.perform(put("/api/characters/alice").contentType(MediaType.APPLICATION_JSON).content("{\"system_rules\":\"override\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("固定规则不允许修改"));
    }

    @Test
    void userListMergesSortsAndDeduplicatesBothStoresAndFallsBackForEmptyData() {
        when(conversations.allUserIds()).thenReturn(Set.of("zoe", "alice"));
        when(memories.allUserIds()).thenReturn(Set.of("bob", "alice"));
        assertThat(controller.users()).extracting(row -> row.get("id")).containsExactly("alice", "bob", "zoe");
        assertThat(controller.users().get(0).get("memory_count")).isEqualTo(3);
        when(conversations.allUserIds()).thenReturn(Set.of());
        when(memories.allUserIds()).thenReturn(Set.of());
        assertThat(controller.users()).extracting(row -> row.get("id")).containsExactly("web_user_001");
    }

    @Test
    void healthCachesDependencyChecksAndReportsAuthFlags() throws Exception {
        when(properties.getAccessKey()).thenReturn("access");
        when(properties.getAdminKey()).thenReturn("admin");
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dependencies.llm").value("ok"))
                .andExpect(jsonPath("$.auth_required").value(true))
                .andExpect(jsonPath("$.admin_key_configured").value(true));
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        verify(model, times(1)).healthy();
    }

    @Test
    void healthReportsUnavailableAndUnconfiguredModels() {
        when(model.healthy()).thenReturn(false);
        assertThat(controller.health().get("dependencies")).isEqualTo(Map.of("llm", "unreachable"));
        ApiController fresh = new ApiController(conversations, memories, characters, model, workflow, properties, mapper, telemetry);
        when(model.configured()).thenReturn(false);
        assertThat(fresh.health().get("dependencies")).isEqualTo(Map.of("llm", "not_configured"));
        verify(model, times(1)).healthy();
    }

    @Test
    void rejectsImagesForTextOnlyModelBeforeOpeningSse() throws Exception {
        mvc.perform(post("/api/chat/alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"image_data\":\"data:image/png;base64,AA==\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("当前模型不支持图片，请切换视觉模型或移除图片后重试"));
        verifyNoInteractions(workflow);
    }

    @ParameterizedTest
    @ValueSource(strings = {"data:image/png;base64,", "data:text/html;base64,AA==", "data:image/svg+xml;base64,AA==", "data:image/png,AA==", "data:image/png;base64,**", "**"})
    void rejectsInvalidImageEncodingBeforeOpeningSse(String image) throws Exception {
        when(model.info()).thenReturn(Map.of("multimodal", true));
        mvc.perform(post("/api/chat/alice").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("message", "hi", "image_data", image))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").isString());
        verifyNoInteractions(workflow);
    }

    @Test
    void boundsDecodedImageSizeAndHistoryBeforeOpeningSse() throws Exception {
        when(model.info()).thenReturn(Map.of("multimodal", true));
        String largeImage = java.util.Base64.getEncoder().encodeToString(new byte[10 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> controller.chat("alice", mapper.createObjectNode().put("message", "hi")
                .put("image_data", largeImage), new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class).hasMessage("图片大小不能超过10MB");
        ObjectNode request = mapper.createObjectNode().put("message", "hi");
        var history = request.putArray("conversation_history");
        for (int index = 0; index < 2001; index++) history.addObject().put("role", "user").put("content", "hi");
        mvc.perform(post("/api/chat/alice").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isUnprocessableEntity());
        history.removeAll().addObject().put("role", "user").put("content", "x".repeat(100_001));
        mvc.perform(post("/api/chat/alice").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isUnprocessableEntity());
        history.removeAll();
        for (int index = 0; index < 201; index++) history.addObject().put("role", "user").put("content", "x".repeat(10_000));
        assertThatThrownBy(() -> controller.chat("alice", request, new MockHttpServletResponse()))
                .isInstanceOf(ApiException.class).hasMessage("对话历史文字总量过大，请缩短历史后重试");
        verifyNoInteractions(workflow);
    }

    private record Route(HttpMethod method, String path, String body) { }
}
