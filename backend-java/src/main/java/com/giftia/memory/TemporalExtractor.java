package com.giftia.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Calendar expressions are evaluated in the product's Chinese locale, not the host timezone. */
final class TemporalExtractor {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Map<String, Integer> SEASONS = ordered(
            "春天", 3, "夏季", 6, "夏天", 6, "秋天", 9,
            "冬季", 12, "冬天", 12, "暑假", 7, "寒假", 1);
    private static final Map<String, Integer> RELATIVE = ordered(
            "今天", 0, "昨天", -1, "前天", -2, "明天", 1, "后天", 2,
            "上周", -7, "这周", 0, "下周", 7, "上个月", -30,
            "这个月", 0, "下个月", 30, "去年", -365, "今年", 0, "明年", 365);
    private static final String[][] COMPOUND = {
            {"去年夏天", "夏天", "-1"}, {"去年冬天", "冬天", "-1"},
            {"今年夏天", "夏天", "0"}, {"今年冬天", "冬天", "0"},
            {"上个暑假", "暑假", "-1"}, {"这个暑假", "暑假", "0"}};

    private TemporalExtractor() { }

    static ObjectNode extract(String text, double timestamp, ObjectMapper mapper) {
        ZonedDateTime reference = Instant.ofEpochMilli((long) (timestamp * 1000)).atZone(ZONE);
        ObjectNode result = mapper.createObjectNode();
        ObjectNode event = result.putObject("event_time");
        ObjectNode context = result.putObject("time_context");
        ObjectNode recurrence = result.putObject("recurrence");
        for (String[] expression : COMPOUND) {
            if (!text.contains(expression[0])) continue;
            int year = reference.getYear() + Integer.parseInt(expression[2]);
            event.put("timestamp", start(year, SEASONS.get(expression[1])));
            event.put("description", expression[0]);
            event.put("precision", "relative");
            context.put("season", expression[1]);
            break;
        }
        if (event.isEmpty()) {
            for (Map.Entry<String, Integer> entry : RELATIVE.entrySet()) {
                if (!text.contains(entry.getKey())) continue;
                event.put("timestamp", (double) reference.plusDays(entry.getValue()).toEpochSecond());
                event.put("description", entry.getKey());
                event.put("precision", "relative");
                break;
            }
        }
        if (event.isEmpty()) {
            for (Map.Entry<String, Integer> entry : SEASONS.entrySet()) {
                if (!text.contains(entry.getKey())) continue;
                int year = reference.getYear() - (entry.getValue() > reference.getMonthValue() ? 1 : 0);
                event.put("timestamp", start(year, entry.getValue()));
                event.put("description", entry.getKey());
                event.put("precision", "approximate");
                context.put("season", entry.getKey());
                break;
            }
        }
        for (String stage : new String[]{"小时候", "童年", "学生时代", "大学时期", "工作后"}) {
            if (text.contains(stage)) {
                context.put("life_stage", stage);
                break;
            }
        }
        String[][] repeats = {{"每天", "daily"}, {"每周", "weekly"}, {"每月", "monthly"},
                {"每年", "yearly"}, {"经常", "irregular"}, {"总是", "irregular"}};
        for (String[] repeat : repeats) {
            if (!text.contains(repeat[0])) continue;
            recurrence.put("is_recurring", true);
            recurrence.put("frequency", repeat[1]);
            break;
        }
        return result;
    }

    private static double start(int year, int month) {
        return LocalDate.of(year, month, 1).atStartOfDay(ZONE).toEpochSecond();
    }

    private static Map<String, Integer> ordered(Object... pairs) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], (Integer) pairs[i + 1]);
        return result;
    }
}
