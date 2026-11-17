package com.giftia.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.giftia.web.ApiException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads and writes the existing Python conversation JSON without a data conversion. */
@Service
public class ConversationService {
    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final String DEFAULT_USER = "web_user_001";
    private static final DateTimeFormatter CREATED_FORMAT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ConversationService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("CREATE TABLE IF NOT EXISTS conversations (user_id TEXT PRIMARY KEY, data TEXT NOT NULL)");
    }

    public synchronized ObjectNode list(String userId) {
        String user = normalizeUser(userId);
        State state = ensure(user);
        List<ObjectNode> summaries = new ArrayList<>();
        state.conversations().fields().forEachRemaining(entry -> {
            JsonNode conversation = entry.getValue();
            ObjectNode item = mapper.createObjectNode();
            item.put("id", entry.getKey());
            item.put("title", conversation.path("title").asText("新对话"));
            item.put("created", conversation.path("created").asText(""));
            item.put("message_count", conversation.path("messages").isArray() ? conversation.path("messages").size() : 0);
            item.put("is_active", entry.getKey().equals(state.currentId()));
            summaries.add(item);
        });
        summaries.sort(Comparator.comparing((ObjectNode item) -> item.path("created").asText()).reversed());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode items = response.putArray("conversations");
        summaries.forEach(items::add);
        response.put("current_id", state.currentId());
        return response;
    }

    public synchronized ObjectNode get(String userId, String conversationId) {
        State state = ensure(normalizeUser(userId));
        return requireConversation(state, conversationId).deepCopy();
    }

    public synchronized ObjectNode create(String userId) {
        String user = normalizeUser(userId);
        State state = ensure(user);
        String id = UUID.randomUUID().toString();
        ObjectNode conversation = emptyConversation();
        state.conversations().set(id, conversation);
        save(user, new State(state.conversations(), id, state.metadata()));
        return mapper.createObjectNode().put("id", id).put("title", "新对话")
                .put("created", conversation.path("created").asText());
    }

    public synchronized ObjectNode rename(String userId, String conversationId, String title) {
        if (title == null || title.strip().isEmpty() || codePointLength(title) > 100) {
            throw new ApiException(422, "会话标题需为1至100字");
        }
        String user = normalizeUser(userId);
        State state = ensure(user);
        requireConversation(state, conversationId).put("title", title.strip());
        save(user, state);
        return mapper.createObjectNode().put("ok", true).put("title", title.strip());
    }

    public synchronized ObjectNode delete(String userId, String conversationId) {
        String user = normalizeUser(userId);
        State state = ensure(user);
        requireConversation(state, conversationId);
        state.conversations().remove(conversationId);
        String currentId = state.currentId();
        if (state.conversations().isEmpty()) {
            currentId = UUID.randomUUID().toString();
            state.conversations().set(currentId, emptyConversation());
        } else if (conversationId.equals(currentId)) {
            currentId = lastConversationId(state.conversations());
        }
        save(user, new State(state.conversations(), currentId, state.metadata()));
        return mapper.createObjectNode().put("ok", true).put("current_id", currentId);
    }

    /** Commit a complete exchange only after generation succeeds. */
    public synchronized ObjectNode appendExchange(String userId, String conversationId, String userMessage,
            String assistantReply, String image) {
        String user = normalizeUser(userId);
        State state = ensure(user);
        String id = conversationId == null || conversationId.isBlank() ? state.currentId() : conversationId;
        if (!state.conversations().has(id)) {
            id = UUID.randomUUID().toString();
            ObjectNode conversation = emptyConversation();
            conversation.put("title", firstCodePoints(userMessage, 20));
            state.conversations().set(id, conversation);
        }
        ObjectNode conversation = requireConversation(state, id);
        ArrayNode messages;
        if (conversation.path("messages").isArray()) {
            messages = (ArrayNode) conversation.get("messages");
        } else {
            messages = conversation.putArray("messages");
        }
        ObjectNode message = messages.addObject().put("role", "user").put("content", userMessage);
        if (image != null && !image.isBlank()) {
            message.put("image", image);
        }
        messages.addObject().put("role", "assistant").put("content", assistantReply);
        if (messages.size() <= 2) {
            conversation.put("title", firstCodePoints(userMessage, 20));
        }
        save(user, new State(state.conversations(), id, state.metadata()));
        return mapper.createObjectNode().put("conversation_id", id);
    }

    public synchronized Set<String> allUserIds() {
        return new TreeSet<>(jdbc.queryForList("SELECT user_id FROM conversations", String.class));
    }

    public synchronized void ensureUser(String userId) {
        ensure(normalizeUser(userId));
    }

    private State ensure(String user) {
        State state = load(user);
        if (state == null || state.conversations().isEmpty()) {
            ObjectNode conversations = mapper.createObjectNode();
            String id = UUID.randomUUID().toString();
            conversations.set(id, emptyConversation());
            state = new State(conversations, id, state == null ? mapper.createObjectNode() : state.metadata());
            save(user, state);
        } else if (state.currentId() == null || !state.conversations().has(state.currentId())) {
            state = new State(state.conversations(), lastConversationId(state.conversations()), state.metadata());
            save(user, state);
        }
        return state;
    }

    private State load(String user) {
        List<String> rows = jdbc.queryForList("SELECT data FROM conversations WHERE user_id = ?", String.class, user);
        if (rows.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(rows.get(0));
            if (root == null || !root.path("conversations").isObject()) {
                throw unreadableConversation();
            }
            ObjectNode conversations = ((ObjectNode) root.get("conversations")).deepCopy();
            List<String> invalid = new ArrayList<>();
            conversations.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isObject()
                        || entry.getValue().has("messages") && !entry.getValue().get("messages").isArray()) {
                    invalid.add(entry.getKey());
                }
            });
            if (!invalid.isEmpty()) throw unreadableConversation();
            ObjectNode metadata = ((ObjectNode) root).deepCopy();
            metadata.remove(List.of("current_id", "conversations"));
            return new State(conversations, root.path("current_id").asText(null), metadata);
        } catch (JsonProcessingException exception) {
            throw unreadableConversation();
        }
    }

    private static ApiException unreadableConversation() {
        log.warn("会话记录格式无效，保留原记录并停止写入；未将记录内容写入日志");
        return new ApiException(500, "会话记录暂时无法读取，原记录已保留");
    }

    private void save(String user, State state) {
        ObjectNode root = state.metadata().deepCopy();
        root.put("current_id", state.currentId());
        root.set("conversations", state.conversations());
        jdbc.update("INSERT OR REPLACE INTO conversations (user_id, data) VALUES (?, ?)", user, root.toString());
    }

    private ObjectNode emptyConversation() {
        ObjectNode conversation = mapper.createObjectNode();
        conversation.put("title", "新对话");
        conversation.putArray("messages");
        conversation.put("created", LocalDateTime.now().format(CREATED_FORMAT));
        return conversation;
    }

    private ObjectNode requireConversation(State state, String id) {
        JsonNode value = id == null ? null : state.conversations().get(id);
        if (value == null || !value.isObject()) {
            throw new ApiException(404, "对话不存在");
        }
        return (ObjectNode) value;
    }

    private static String lastConversationId(ObjectNode conversations) {
        String last = null;
        var names = conversations.fieldNames();
        while (names.hasNext()) last = names.next();
        return last;
    }

    private static String normalizeUser(String userId) {
        String user = userId == null ? "" : userId.strip();
        if (user.isEmpty()) return DEFAULT_USER;
        if (codePointLength(user) > 200) throw new ApiException(422, "用户标识无效");
        return user;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String firstCodePoints(String value, int limit) {
        if (value == null || value.isEmpty()) return "新对话";
        int end = value.offsetByCodePoints(0, Math.min(limit, codePointLength(value)));
        return value.substring(0, end);
    }

    private record State(ObjectNode conversations, String currentId, ObjectNode metadata) {}
}
