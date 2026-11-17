package com.giftia.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Keeps the Python JSON representation, including unknown fields, when updating a record. */
final class MemoryRecord {
    private final ObjectNode data;

    MemoryRecord(String id, ObjectNode source, double now) {
        data = source.deepCopy();
        data.put("id", id);
        if (!data.path("content").isTextual()) throw new IllegalArgumentException("Stored memory content is not text");
        data.put("emotion", MemoryAlgorithms.normalizeEmotion(data.path("emotion").asText("neutral")));
        String category = data.path("category").asText("fact");
        data.put("category", MemoryAlgorithms.CATEGORIES.contains(category) ? category : "fact");
        data.put("importance", MemoryAlgorithms.clamp(number("importance", .5)));
        data.put("emotion_intensity", MemoryAlgorithms.clamp(number("emotion_intensity", .5)));
        data.put("access_count", Math.max(0, data.path("access_count").asInt(0)));
        data.put("created_at", number("created_at", now));
        data.put("last_accessed", number("last_accessed", 0));
        int layer = data.path("layer").asInt(3);
        data.put("layer", layer >= 1 && layer <= 3 ? layer : 3);
        data.put("is_consolidated", data.path("is_consolidated").asBoolean(false));
        if (!data.path("tags").isArray()) data.putArray("tags");
        if (!data.path("temporal_data").isObject()) data.putObject("temporal_data");
        if (embedding() == null) data.remove("embedding");
    }

    private double number(String key, double fallback) {
        JsonNode value = data.path(key);
        double number = value.isNumber() ? value.asDouble() : fallback;
        return Double.isFinite(number) ? number : fallback;
    }

    String id() { return data.path("id").asText(); }
    String content() { return data.path("content").asText(); }
    String emotion() { return data.path("emotion").asText(); }
    String category() { return data.path("category").asText(); }
    double importance() { return data.path("importance").asDouble(); }
    double emotionIntensity() { return data.path("emotion_intensity").asDouble(); }
    double createdAt() { return data.path("created_at").asDouble(); }
    double lastAccessed() { return data.path("last_accessed").asDouble(); }
    int accessCount() { return data.path("access_count").asInt(); }
    int layer() { return data.path("layer").asInt(); }
    boolean consolidated() { return data.path("is_consolidated").asBoolean(); }
    ObjectNode json() { return data.deepCopy(); }

    List<Double> embedding() {
        JsonNode vector = data.path("embedding");
        if (!vector.isArray() || vector.isEmpty()) return null;
        List<Double> result = new ArrayList<>(vector.size());
        for (JsonNode value : vector) {
            if (!value.isNumber() || !Double.isFinite(value.asDouble())) return null;
            result.add(value.asDouble());
        }
        return result;
    }

    void embedding(List<Double> vector) {
        if (!MemoryAlgorithms.validVector(vector)) return;
        ArrayNode values = data.putArray("embedding");
        vector.forEach(values::add);
    }

    void accessed(double now) {
        data.put("access_count", accessCount() + 1);
        data.put("last_accessed", now);
    }

    void layer(int layer) { data.put("layer", layer); }
    void importance(double value) { data.put("importance", MemoryAlgorithms.clamp(value)); }

    ObjectNode detail() {
        ObjectNode result = json();
        result.remove(List.of("embedding", "layer"));
        result.put("emotion_emoji", MemoryAlgorithms.EMOJIS.getOrDefault(emotion(), "😐"));
        return result;
    }
}
