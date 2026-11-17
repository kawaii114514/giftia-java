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
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequestMapping({"/api", "/api/v1"})
public class ApiController {
    private static final String DEFAULT_USER = "web_user_001";
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_IMAGE_BASE64_LENGTH = ((MAX_IMAGE_BYTES + 2) / 3) * 4;
    private static final int MAX_HISTORY_MESSAGES = 2000;
    private static final int MAX_HISTORY_TEXT = 2_000_000;
    private final ConversationService conversations;
    private final MemoryService memories;
    private final CharacterService characters;
    private final ModelGateway model;
    private final GiftiaWorkflow workflow;
    private final GiftiaProperties properties;
    private final ObjectMapper mapper;
    private final ApiTelemetry telemetry;
    private volatile Map<String, Object> cachedHealth;
    private volatile long healthAt;

    public ApiController(ConversationService conversations, MemoryService memories,
                         CharacterService characters, ModelGateway model, GiftiaWorkflow workflow,
                         GiftiaProperties properties, ObjectMapper mapper, ApiTelemetry telemetry) {
        this.conversations = conversations;
        this.memories = memories;
        this.characters = characters;
        this.model = model;
        this.workflow = workflow;
        this.properties = properties;
        this.mapper = mapper;
        this.telemetry = telemetry;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> existing = cachedHealth;
        if (existing != null && System.nanoTime() - healthAt < 30_000_000_000L) return existing;
        synchronized (this) {
            if (cachedHealth != null && System.nanoTime() - healthAt < 30_000_000_000L) return cachedHealth;
            String status = !model.configured() ? "not_configured" : model.healthy() ? "ok" : "unreachable";
            cachedHealth = Map.of("status", "ok", "dependencies", Map.of("llm", status),
                    "multimodal", model.info().getOrDefault("multimodal", false),
                    "auth_required", present(properties.getAccessKey()),
                    "admin_key_configured", present(properties.getAdminKey()));
            healthAt = System.nanoTime();
            return cachedHealth;
        }
    }

    @PostMapping(value = "/chat/{user_id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(@PathVariable("user_id") String user,
                                                     @RequestBody JsonNode request,
                                                     HttpServletResponse response) {
        validateChat(request);
        if (!model.configured()) throw new ApiException(500, "未配置 LLM_API_KEY");
        JsonNode image = request.get("image_data");
        if (image != null && image.isTextual() && !image.asText().isBlank()
                && !Boolean.TRUE.equals(model.info().get("multimodal")))
            throw new ApiException(422, "当前模型不支持图片，请切换视觉模型或移除图片后重试");
        StreamingResponseBody stream = output -> {
            try {
                workflow.stream(user(user), request, event -> {
                    try {
                        output.write(("data: " + mapper.writeValueAsString(event) + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        response.flushBuffer();
                    } catch (java.io.IOException disconnected) {
                        throw new UncheckedIOException(disconnected);
                    }
                });
            } catch (UncheckedIOException disconnected) {
                throw disconnected.getCause();
            } catch (Exception failure) {
                telemetry.addLog("ERROR", "对话生成失败 (" + failure.getClass().getSimpleName() + ")");
                output.write(("data: " + mapper.writeValueAsString(Map.of("type", "error", "text",
                        "回复生成失败，请稍后重试")) + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                response.flushBuffer();
            }
        };
        return ResponseEntity.ok().contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                .header("Cache-Control", "no-cache").header("Connection", "keep-alive")
                .header("X-Accel-Buffering", "no").body(stream);
    }

    @GetMapping("/conversations/{user_id}")
    public ObjectNode conversationList(@PathVariable("user_id") String user) { return conversations.list(user(user)); }

    @GetMapping("/conversations/{user_id}/{conv_id}")
    public ObjectNode conversation(@PathVariable("user_id") String user, @PathVariable("conv_id") String id) {
        return conversations.get(user(user), id);
    }

    @PostMapping("/conversations/{user_id}")
    public ObjectNode newConversation(@PathVariable("user_id") String user) { return conversations.create(user(user)); }

    @PatchMapping("/conversations/{user_id}/{conv_id}")
    public ObjectNode rename(@PathVariable("user_id") String user, @PathVariable("conv_id") String id,
                             @RequestBody JsonNode request) {
        return conversations.rename(user(user), id, text(request, "title", 1, 100));
    }

    @DeleteMapping("/conversations/{user_id}/{conv_id}")
    public ObjectNode deleteConversation(@PathVariable("user_id") String user, @PathVariable("conv_id") String id) {
        return conversations.delete(user(user), id);
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        TreeSet<String> ids = new TreeSet<>(conversations.allUserIds());
        ids.addAll(memories.allUserIds());
        if (ids.isEmpty()) ids.add(DEFAULT_USER);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : ids) {
            ObjectNode stats = memories.stats(id);
            result.add(Map.of("id", id, "memory_count", stats.path("total").asInt(0),
                    "consolidated_count", stats.path("consolidated_count").asInt(0),
                    "avg_importance", stats.path("avg_importance").asDouble(0)));
        }
        return result;
    }

    @PostMapping("/users")
    public Map<String, Object> newUser(@RequestParam("user_id") String id) {
        checkLength(id, 1, 50, "用户标识");
        if (id.isBlank()) throw new ApiException(422, "用户标识不能为空");
        String user = id.trim();
        conversations.ensureUser(user);
        return Map.of("ok", true, "user_id", user);
    }

    @GetMapping("/memory/{user_id}/stats")
    public ObjectNode stats(@PathVariable("user_id") String user) { return memories.stats(user(user)); }

    @GetMapping("/memory/{user_id}/detail")
    public ObjectNode detail(@PathVariable("user_id") String user) { return memories.detail(user(user)); }

    @GetMapping("/memory/{user_id}/layers")
    public ObjectNode layers(@PathVariable("user_id") String user) { return memories.layers(user(user)); }

    @DeleteMapping("/memory/{user_id}")
    public ObjectNode clear(@PathVariable("user_id") String user) { return memories.clear(user(user)); }

    @PatchMapping("/memory/{user_id}/{memory_id}/layer")
    public ObjectNode changeLayer(@PathVariable("user_id") String user, @PathVariable("memory_id") String id,
                                  @RequestParam("new_layer") int layer) {
        if (layer < 1 || layer > 3) throw new ApiException(400, "无效的层级");
        return memories.changeLayer(user(user), id, layer);
    }

    @GetMapping("/profile/{user_id}")
    public ObjectNode profile(@PathVariable("user_id") String user) { return memories.profile(user(user)); }

    @PutMapping("/profile/{user_id}")
    public ObjectNode updateProfile(@PathVariable("user_id") String user, @RequestBody JsonNode request) {
        if (!request.isObject()) throw new ApiException(422, "画像配置必须为对象");
        return memories.updateProfile(user(user), request);
    }

    @GetMapping("/characters/{user_id}")
    public ObjectNode character(@PathVariable("user_id") String user) { return characters.config(user); }

    @PutMapping("/characters/{user_id}")
    public ObjectNode updateCharacter(@PathVariable("user_id") String user, @RequestBody JsonNode request) {
        return characters.update(user, request);
    }

    @GetMapping("/config/prompt")
    public ObjectNode prompt() { return characters.legacyConfig(); }

    @PutMapping("/config/prompt")
    public ObjectNode savePrompt(@RequestBody JsonNode request) {
        return characters.saveLegacy(text(request, "prompt", 0, 5000));
    }

    @GetMapping("/config/model")
    public Map<String, Object> model() { return model.info(); }

    @GetMapping("/config/model-presets")
    public Map<String, Object> models() { return model.presets(); }

    @GetMapping("/logs")
    public Map<String, Object> logs(@RequestParam(name = "limit", defaultValue = "200") int limit) {
        if (limit < 1 || limit > 500) throw new ApiException(422, "日志条数需在1至500之间");
        return Map.of("logs", telemetry.logs(limit));
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() { return telemetry.metrics(); }

    private static void validateChat(JsonNode request) {
        text(request, "message", 1, 10000);
        nullableText(request, "conversation_id");
        nullableText(request, "image_data");
        validateImage(request.path("image_data").asText(""));
        JsonNode history = request.get("conversation_history");
        if (history == null) return;
        if (!history.isArray()) throw new ApiException(422, "conversation_history必须为数组");
        if (history.size() > MAX_HISTORY_MESSAGES) throw new ApiException(422, "对话历史不能超过2000条");
        int historyLength = 0;
        for (JsonNode message : history) {
            if (!message.isObject() || !message.path("role").isTextual()
                    || !message.path("content").isTextual()) throw new ApiException(422, "对话历史格式无效");
            String role = message.get("role").asText();
            if (!role.equals("user") && !role.equals("assistant")) throw new ApiException(422, "对话角色无效");
            String content = message.get("content").asText();
            checkLength(content, 0, 100_000, "历史消息");
            historyLength += content.codePointCount(0, content.length());
            if (historyLength > MAX_HISTORY_TEXT) throw new ApiException(422, "对话历史文字总量过大，请缩短历史后重试");
        }
    }

    private static void validateImage(String image) {
        if (image.isBlank()) return;
        String payload = image;
        if (image.startsWith("data:")) {
            int comma = image.indexOf(',');
            if (comma < 0 || !image.substring(0, comma).matches("data:image/(png|jpeg|webp|gif);base64"))
                throw new ApiException(422, "图片必须为有效的image类型base64 Data URL");
            payload = image.substring(comma + 1);
        }
        if (payload.isEmpty()) throw new ApiException(422, "图片内容不能为空");
        if (payload.length() > MAX_IMAGE_BASE64_LENGTH) throw new ApiException(422, "图片大小不能超过10MB");
        try {
            if (Base64.getDecoder().decode(payload).length > MAX_IMAGE_BYTES)
                throw new ApiException(422, "图片大小不能超过10MB");
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(422, "图片base64编码无效");
        }
    }

    private static void nullableText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value != null && !value.isNull() && !value.isTextual())
            throw new ApiException(422, field + "必须为文本");
    }

    private static String text(JsonNode object, String field, int minimum, int maximum) {
        if (object == null || !object.isObject() || !object.path(field).isTextual())
            throw new ApiException(422, field + "必须为文本");
        String value = object.get(field).asText();
        checkLength(value, minimum, maximum, field);
        return value;
    }

    private static void checkLength(String value, int minimum, int maximum, String field) {
        int size = value.codePointCount(0, value.length());
        if (size < minimum || size > maximum) throw new ApiException(422,
                field + "长度需在" + minimum + "至" + maximum + "之间");
    }

    private static String user(String value) {
        return value == null || value.isBlank() ? DEFAULT_USER : value.trim();
    }

    private static boolean present(String value) { return value != null && !value.isEmpty(); }
}
