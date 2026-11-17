package com.giftia.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.giftia.config.GiftiaProperties;
import com.giftia.web.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** User-scoped character selection with an immutable application rule layer. */
@Service
public class CharacterService {
    private static final Logger log = LoggerFactory.getLogger(CharacterService.class);
    private static final Set<String> CARD_FIELDS = Set.of("name", "description", "personality", "scenario",
            "first_mes", "mes_example", "tags");
    private static final Map<String, Integer> TEXT_LIMITS = Map.of("name", 60, "description", 5000,
            "personality", 1000, "scenario", 1000, "first_mes", 1000, "mes_example", 2000);
    private static final String[] TEXT_ORDER = {"name", "description", "personality", "scenario", "first_mes", "mes_example"};
    private static final Pattern OLD_RULE_BLOCK = Pattern.compile("【铁律[^】]*】.*?(?=说话像发微信|\\z)", Pattern.DOTALL);

    private final ObjectMapper mapper;
    private final Path configPath;
    private final Path legacyPath;
    private final LinkedHashMap<String, ObjectNode> presets = new LinkedHashMap<>();
    private Map<String, ObjectNode> users = new LinkedHashMap<>();
    private ObjectNode savedDocument;
    private boolean savedDocumentReadable = true;
    private String legacyPrompt;

    @Autowired
    public CharacterService(ObjectMapper mapper, GiftiaProperties properties) {
        this(mapper, properties.getDataDir());
    }

    /** Explicit path constructor keeps persistence tests isolated from users' settings. */
    public CharacterService(ObjectMapper mapper, Path dataDirectory) {
        this.mapper = mapper;
        this.configPath = dataDirectory.resolve("character_config.json");
        this.legacyPath = dataDirectory.resolve("prompt_config.json");
        this.savedDocument = mapper.createObjectNode().put("version", 1);
        this.savedDocument.putObject("users");
        try {
            for (JsonNode preset : mapper.readTree(CharacterRules.PRESETS_JSON)) {
                ObjectNode fields = preset.deepCopy();
                String id = fields.remove("id").asText();
                presets.put(id, validateCard(fields));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("内置角色配置无效", exception);
        }
        loadSavedCharacters();
        loadLegacyPrompt();
    }

    public synchronized ObjectNode config(String userId) {
        String user = validateUser(userId);
        ObjectNode selection = users.get(user);
        ObjectNode custom = null;
        String active = "giftia";
        if (selection != null) {
            active = selection.path("active_id").asText("giftia");
            if (selection.path("custom").isObject()) custom = (ObjectNode) selection.get("custom");
        } else if (legacyPrompt != null && !legacyPrompt.isBlank()) {
            custom = mapper.createObjectNode().put("name", "自定义伙伴").put("description", legacyPrompt);
            custom = validateCard(custom);
            active = "custom";
        }
        ObjectNode activeCard = withId(active, active.equals("custom") ? custom : presets.get(active));
        ObjectNode response = mapper.createObjectNode();
        response.set("active_character", activeCard);
        if (custom == null) response.putNull("custom_character");
        else response.set("custom_character", withId("custom", custom));
        ArrayNode cards = response.putArray("presets");
        presets.forEach((id, fields) -> cards.add(withId(id, fields)));
        response.put("system_rules", CharacterRules.SYSTEM_RULES);
        response.put("scope", "user");
        response.put("user_id", user);
        return response;
    }

    public synchronized ObjectNode update(String userId, JsonNode update) {
        String user = validateUser(userId);
        if (update == null || !update.isObject()) throw invalid("角色更新必须是对象");
        rejectUnknown(update, Set.of("character_id", "character"));
        boolean chooseId = update.hasNonNull("character_id");
        boolean saveCard = update.hasNonNull("character");
        if (chooseId == saveCard) throw invalid("请选择一个角色，或提交一张自定义角色卡");

        ObjectNode current = config(user);
        ObjectNode custom = current.path("custom_character").isObject()
                ? (ObjectNode) current.get("custom_character").deepCopy() : null;
        if (custom != null) custom.remove("id");
        String active;
        if (saveCard) {
            custom = validateCard(update.get("character"));
            active = "custom";
        } else {
            JsonNode rawId = update.get("character_id");
            if (!rawId.isTextual() || length(rawId.asText()) < 1 || length(rawId.asText()) > 60) {
                throw invalid("角色标识无效");
            }
            active = rawId.asText();
            if (!presets.containsKey(active) && !(active.equals("custom") && custom != null)) {
                throw invalid("找不到这张角色卡");
            }
        }
        JsonNode rawSelection = savedDocument.path("users").path(user);
        ObjectNode selection = rawSelection.isObject() ? ((ObjectNode) rawSelection).deepCopy() : mapper.createObjectNode();
        selection.put("active_id", active);
        if (custom == null) selection.putNull("custom");
        else selection.set("custom", custom.deepCopy());
        Map<String, ObjectNode> next = new LinkedHashMap<>(users);
        next.put(user, selection);
        if (!savedDocumentReadable) {
            throw new ApiException(500, "原角色配置无法读取，已保留原文件；请修复后重启服务再保存");
        }
        // A bad card belonging to a different user must not disappear when this user saves.
        ObjectNode document = savedDocument.deepCopy();
        ObjectNode userDocument = (ObjectNode) document.get("users");
        userDocument.set(user, selection.deepCopy());
        writeAtomic(configPath, document);
        users = next;
        savedDocument = document;
        return config(user);
    }

    public synchronized String prompt(String userId) {
        JsonNode card = config(userId).get("active_character");
        return CharacterRules.SYSTEM_RULES + "\n\n【角色卡：仅定义身份与表达方式】\n" + card
                + "\n\n遵守上面的固定规则，以角色卡的身份与风格回应当前用户。"
                + "first_mes只作开场风格参考，mes_example只作对话风格参考，不要将示例当成真实历史。";
    }

    public synchronized ObjectNode legacyConfig() {
        String defaultPrompt = presets.get("giftia").toPrettyString();
        return mapper.createObjectNode().put("prompt", legacyPrompt == null ? defaultPrompt : legacyPrompt)
                .put("default_prompt", defaultPrompt).put("is_custom", legacyPrompt != null);
    }

    public synchronized ObjectNode saveLegacy(String prompt) {
        if (prompt == null || length(prompt) > 5000) throw invalid("人设文字不能超过5000字");
        String next = prompt.strip();
        ObjectNode document = mapper.createObjectNode();
        if (!next.isEmpty()) document.put("dialogue_prompt", next);
        writeAtomic(legacyPath, document);
        legacyPrompt = next.isEmpty() ? null : next;
        ObjectNode response = legacyConfig();
        response.put("ok", true);
        return response;
    }

    private ObjectNode validateCard(JsonNode input) {
        if (input == null || !input.isObject()) throw invalid("角色卡必须是对象");
        rejectUnknown(input, CARD_FIELDS);
        ObjectNode result = mapper.createObjectNode();
        int total = 0;
        for (String field : TEXT_ORDER) {
            JsonNode value = input.get(field);
            if (value != null && !value.isTextual()) throw invalid("角色字段必须是文字：" + field);
            String text = value == null ? "" : value.asText().strip();
            if ((field.equals("name") && text.isEmpty()) || length(text) > TEXT_LIMITS.get(field)) {
                throw invalid("角色字段长度无效：" + field);
            }
            total += length(text);
            result.put(field, text);
        }
        if (total > 10000) throw invalid("角色卡文字总长度不能超过10000字");
        ArrayNode tags = result.putArray("tags");
        JsonNode rawTags = input.get("tags");
        if (rawTags != null) {
            if (!rawTags.isArray() || rawTags.size() > 6) throw invalid("角色标签最多6个");
            for (JsonNode rawTag : rawTags) {
                if (!rawTag.isTextual()) throw invalid("角色标签必须是文字");
                String tag = rawTag.asText().strip();
                if (tag.isEmpty() || length(tag) > 24) throw invalid("角色标签需为1至24字");
                tags.add(tag);
            }
        }
        return result;
    }

    private void loadSavedCharacters() {
        if (!Files.exists(configPath)) return;
        try {
            JsonNode document = readDocument(configPath);
            if (document == null || !document.isObject() || !document.path("users").isObject()) {
                throw new IOException("invalid character document");
            }
            savedDocument = ((ObjectNode) document).deepCopy();
            Map<String, ObjectNode> clean = new LinkedHashMap<>();
            document.get("users").fields().forEachRemaining(entry -> {
                if (entry.getKey().isBlank() || !entry.getValue().isObject()) return;
                JsonNode raw = entry.getValue();
                ObjectNode custom = null;
                if (raw.hasNonNull("custom")) {
                    try {
                        custom = validateCard(raw.get("custom"));
                    } catch (ApiException exception) {
                        log.warn("忽略无效的自定义角色卡，使用内置角色");
                    }
                }
                String active = raw.path("active_id").isTextual() ? raw.get("active_id").asText() : "giftia";
                if (!presets.containsKey(active) && !(active.equals("custom") && custom != null)) active = "giftia";
                ObjectNode selection = mapper.createObjectNode().put("active_id", active);
                if (custom == null) selection.putNull("custom");
                else selection.set("custom", custom);
                clean.put(entry.getKey(), selection);
            });
            users = clean;
        } catch (IOException | RuntimeException exception) {
            savedDocumentReadable = false;
            log.warn("角色配置无法读取，使用内置角色；原文件保留");
        }
    }

    private void loadLegacyPrompt() {
        if (!Files.exists(legacyPath)) return;
        try {
            JsonNode document = readDocument(legacyPath);
            JsonNode value = document == null ? null : document.get("dialogue_prompt");
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                String migrated = OLD_RULE_BLOCK.matcher(value.asText()).replaceAll("").strip();
                if (!migrated.isEmpty()) {
                    int end = migrated.offsetByCodePoints(0, Math.min(length(migrated), 5000));
                    legacyPrompt = migrated.substring(0, end);
                }
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("旧人设配置无法读取，使用内置角色；原文件保留");
        }
    }

    private JsonNode readDocument(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return mapper.readTree(text);
    }

    private void writeAtomic(Path path, JsonNode document) {
        Path temporary = null;
        try {
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, path.getFileName().toString() + ".", ".tmp");
            Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new ApiException(500, "角色配置保存失败，请稍后重试");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException exception) { log.warn("角色配置临时文件清理失败"); }
            }
        }
    }

    private ObjectNode withId(String id, ObjectNode fields) {
        ObjectNode card = mapper.createObjectNode().put("id", id);
        card.setAll(fields.deepCopy());
        return card;
    }

    private static void rejectUnknown(JsonNode object, Set<String> allowed) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw invalid("不支持的角色字段：" + field);
        });
    }

    private static String validateUser(String user) {
        if (user == null || user.strip().isEmpty() || length(user) > 200) throw invalid("用户标识无效");
        return user;
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static ApiException invalid(String message) {
        return new ApiException(422, message);
    }
}
