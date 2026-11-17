package com.giftia.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure scoring and extraction rules shared by persistent memory operations. */
final class MemoryAlgorithms {
    static final Set<String> CATEGORIES = Set.of("emotion", "fact", "relationship", "event", "preference", "goal", "concern");
    static final Map<String, String> EMOJIS = Map.ofEntries(
            Map.entry("happy", "😊"), Map.entry("sad", "😢"), Map.entry("anxious", "😰"),
            Map.entry("angry", "😠"), Map.entry("neutral", "😐"), Map.entry("excited", "🤩"),
            Map.entry("fearful", "😨"), Map.entry("grateful", "🙏"), Map.entry("lonely", "😔"),
            Map.entry("hopeful", "🌟"), Map.entry("stressed", "😫"), Map.entry("relieved", "😌"));
    static final List<String> INFO = List.of("叫", "是", "喜欢", "讨厌", "工作", "住", "在", "有", "毕业于",
            "来自", "年龄", "岁", "职业", "专业", " hobby", "兴趣", "家人", "朋友", "同事", "同学");
    private static final Map<String, List<String>> EMOTION_WORDS = emotionWords();
    private static final String[][] FLIPS = {
            {"喜欢", "没牵", "没拉", "没在一起", "不喜欢我", "拒绝", "没结果", "没回应", "单相思", "暗恋", "手都没牵"},
            {"爱", "不爱", "分手", "离开", "拒绝", "单相思", "没结果"},
            {"努力", "失败", "没用", "白费", "不行", "被拒", "碰壁", "落空", "没结果", "找不到", "还是没"},
            {"期待", "落空", "失望", "没实现", "泡汤", "没了"}, {"希望", "破灭", "没了", "失望", "落空"},
            {"成功", "没成功", "不成功", "失败"}};

    private MemoryAlgorithms() { }

    record Emotion(String name, double intensity) { }

    static String normalizeEmotion(String emotion) {
        if (emotion == null) return "neutral";
        String value = emotion.trim().toLowerCase(Locale.ROOT);
        if (EMOJIS.containsKey(value)) return value;
        return switch (value) {
            case "开心", "快乐", "高兴", "愉快" -> "happy";
            case "难过", "悲伤", "伤心", "失落", "沮丧" -> "sad";
            case "焦虑", "紧张", "担忧" -> "anxious";
            case "生气", "愤怒", "烦躁" -> "angry";
            case "兴奋", "激动" -> "excited";
            case "害怕", "恐惧" -> "fearful";
            case "感恩", "感谢", "谢谢" -> "grateful";
            case "孤独", "寂寞" -> "lonely";
            case "希望", "期待" -> "hopeful";
            case "压力", "疲惫" -> "stressed";
            case "放心", "安心" -> "relieved";
            default -> "neutral";
        };
    }

    static Emotion emotion(String text) {
        for (String[] flip : FLIPS) {
            if (!text.contains(flip[0])) continue;
            for (int i = 1; i < flip.length; i++) if (text.contains(flip[i])) return new Emotion("sad", .6);
        }
        String winner = "neutral";
        double highest = 0;
        for (Map.Entry<String, List<String>> entry : EMOTION_WORDS.entrySet()) {
            double score = 0;
            for (String word : entry.getValue()) {
                if (!text.contains(word)) continue;
                score += .3;
                for (String modifier : List.of("非常", "特别", "超级", "极其", "万分", "太", "真的很", "特别", "格外", "十分"))
                    if (text.contains(modifier)) score += .2;
                for (String negator : List.of("不", "没", "别", "别", "别要", "没有", "并非", "并不"))
                    if (text.contains(negator + word)) score -= .2;
            }
            if (score > highest) {
                highest = score;
                winner = entry.getKey();
            }
        }
        return new Emotion(winner, clamp(highest));
    }

    static double importance(String content, double intensity, int accesses) {
        long information = INFO.stream().filter(content::contains).count();
        double value = clamp(intensity) * .4 + Math.min(.3, information * .05)
                + Math.min(.2, Math.max(0, accesses) * .04) + Math.min(.1, content.codePointCount(0, content.length()) / 200.0);
        return Math.round(clamp(value) * 1000) / 1000.0;
    }

    static int automaticLayer(double importance, double intensity) {
        if (importance >= .8 || intensity >= .8) return 1;
        if (importance >= .6 || intensity >= .7) return 2;
        return 3;
    }

    static List<String> tags(String content) {
        Set<String> tags = new LinkedHashSet<>();
        INFO.stream().filter(content::contains).forEach(tags::add);
        for (List<String> words : EMOTION_WORDS.values()) {
            words.stream().filter(content::contains).findFirst().ifPresent(tags::add);
        }
        return tags.stream().limit(5).toList();
    }

    static double keywordScore(MemoryRecord memory, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        String content = memory.content().toLowerCase(Locale.ROOT);
        String cleanQuery = clean(q);
        String cleanContent = clean(content);
        if (cleanQuery.length() >= 2 && cleanContent.contains(cleanQuery)) return .8;
        if (cleanContent.length() >= 2 && cleanQuery.contains(cleanContent)) return .8;
        int common = 0;
        for (int length = 2; length <= Math.min(4, cleanQuery.length()); length++) {
            for (int i = 0; i + length <= cleanQuery.length(); i++) {
                if (cleanContent.contains(cleanQuery.substring(i, i + length))) common++;
            }
        }
        double multiplier = .6 + memory.importance() * .4;
        if (common > 0) return common / (double) Math.max(1, cleanQuery.length()) * multiplier;
        Set<String> queryWords = words(q);
        Set<String> contentWords = words(content);
        Set<String> intersection = new HashSet<>(queryWords);
        intersection.retainAll(contentWords);
        if (intersection.isEmpty()) return 0;
        Set<String> union = new HashSet<>(queryWords);
        union.addAll(contentWords);
        return intersection.size() / (double) union.size() * multiplier;
    }

    static boolean validVector(List<Double> vector) {
        return vector != null && !vector.isEmpty() && vector.stream().allMatch(v -> v != null && Double.isFinite(v));
    }

    static double cosine(List<Double> a, List<Double> b) {
        if (!validVector(a) || !validVector(b) || a.size() != b.size()) return 0;
        double dot = 0, squareA = 0, squareB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            squareA += a.get(i) * a.get(i);
            squareB += b.get(i) * b.get(i);
        }
        if (squareA == 0 || squareB == 0) return 0;
        double score = dot / (Math.sqrt(squareA) * Math.sqrt(squareB));
        return Double.isFinite(score) ? Math.max(-1, Math.min(1, score)) : 0;
    }

    static List<MemoryRecord> hybrid(List<MemoryRecord> memories, List<String> queries,
                                     List<List<Double>> queryVectors, String original, double now, int limit) {
        Map<String, Double> rrf = new LinkedHashMap<>();
        Map<String, List<Double>> vectors = new LinkedHashMap<>();
        memories.forEach(memory -> vectors.put(memory.id(), memory.embedding()));
        for (int q = 0; q < queries.size(); q++) {
            List<Scored> semantic = new ArrayList<>();
            List<Scored> keyword = new ArrayList<>();
            List<Double> queryVector = q < queryVectors.size() ? queryVectors.get(q) : null;
            for (MemoryRecord memory : memories) {
                double similarity = cosine(queryVector, vectors.get(memory.id()));
                if (similarity > .1) semantic.add(new Scored(memory, similarity));
                double match = keywordScore(memory, queries.get(q));
                if (match > 0) keyword.add(new Scored(memory, match));
            }
            addRanks(rrf, semantic);
            addRanks(rrf, keyword);
        }
        double max = rrf.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        String queryEmotion = emotion(original).name();
        return memories.stream().filter(m -> rrf.containsKey(m.id()))
                .map(m -> new Scored(m, rerank(m, rrf.get(m.id()) / max, queryEmotion, now)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(Math.max(0, limit)).map(Scored::memory).toList();
    }

    private static void addRanks(Map<String, Double> rrf, List<Scored> results) {
        results.sort(Comparator.comparingDouble(Scored::score).reversed());
        for (int i = 0; i < results.size(); i++) rrf.merge(results.get(i).memory().id(), 1.0 / (61 + i), Double::sum);
    }

    static double rerank(MemoryRecord memory, double normalizedRrf, String emotion, double now) {
        double strength = switch (memory.layer()) { case 1 -> 10; case 2 -> 2; default -> 1; };
        double weight = switch (memory.layer()) { case 1 -> 1; case 2 -> .8; default -> .5; };
        double since = memory.lastAccessed() > 0 ? memory.lastAccessed() : memory.createdAt();
        double hours = Math.max(0, (now - since) / 3600);
        double emotionScore = emotion.equals("neutral") ? 0 : emotion.equals(memory.emotion()) ? 1
                : memory.emotion().equals("neutral") ? 0 : .3;
        return normalizedRrf * .5 + Math.exp(-hours / (strength * 24 + 1)) * .2
                + emotionScore * .15 + memory.importance() * .1 + weight * .05;
    }

    static double retention(MemoryRecord memory, double now) {
        double since = memory.lastAccessed() > 0 ? memory.lastAccessed() : memory.createdAt();
        double hours = Math.max(0, (now - since) / 3600);
        double strength = .3 + memory.importance() * .5 + Math.min(memory.accessCount() * .15, 1);
        return clamp(Math.exp(-hours / (strength * 24 + 1)));
    }

    static List<String> ruleFacts(String input) {
        Set<String> facts = new LinkedHashSet<>();
        List<String> indicators = List.of("我叫", "我是", "我喜欢", "我不喜欢", "我的职业", "我的工作",
                "我养了", "我有", "我在", "我从事", "我来自", "我结婚", "我单身", "我希望", "我害怕", "我担心", "我讨厌");
        List<String> aiSubjects = List.of("你会", "你可以", "你能", "你总是", "你应该", "你说的", "你回", "你跟");
        for (String sentence : input.split("[。！？!?，,;；\\r\\n]")) {
            String fact = sentence.strip();
            if (fact.length() < 3 || fact.length() > 100 || indicators.stream().noneMatch(fact::contains)) continue;
            if (aiSubjects.stream().anyMatch(p -> fact.startsWith(p) || fact.contains(" " + p))) continue;
            facts.add("[关于用户] " + fact);
        }
        for (String emotion : List.of("焦虑", "孤独", "悲伤", "痛苦", "迷茫", "压力大", "崩溃", "失望", "愤怒", "抑郁")) {
            if (input.contains(emotion)) {
                facts.add("[用户情感状态] 用户正在经历" + emotion);
                break;
            }
        }
        return new ArrayList<>(facts);
    }

    static List<String> filterFacts(List<String> facts) {
        Set<String> prefixes = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String fact : facts) {
            if (fact == null || fact.strip().length() < 5) continue;
            String value = fact.strip();
            if (value.endsWith("，") || value.endsWith(",")) continue;
            if (prefixes.add(limit(value, 20))) result.add(limit(value, 500));
            if (result.size() == 5) break;
        }
        return result;
    }

    static double clamp(double value) { return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0; }
    static String limit(String value, int length) {
        if (value == null) return "";
        int points = value.codePointCount(0, value.length());
        return points <= length ? value : value.substring(0, value.offsetByCodePoints(0, length));
    }

    private static String clean(String text) {
        StringBuilder clean = new StringBuilder();
        text.codePoints().filter(Character::isLetterOrDigit).forEach(clean::appendCodePoint);
        return clean.toString();
    }
    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>(List.of(text.replaceAll("[，。！？]", " ").split("\\s+")));
        words.remove("");
        return words;
    }
    private record Scored(MemoryRecord memory, double score) { }

    private static Map<String, List<String>> emotionWords() {
        Map<String, List<String>> words = new LinkedHashMap<>();
        words.put("happy", List.of("开心", "快乐", "高兴", "愉快", "幸福", "满足", "太好了", "棒", "爽", "笑", "好玩", "有趣", "惊喜", "顺利", "成功"));
        words.put("sad", List.of("难过", "悲伤", "伤心", "失落", "沮丧", "哭", "痛苦", "绝望", "无奈", "失望", "心碎", "眼泪", "不开心", "郁闷", "低落", "失败", "挫败", "遗憾", "错过", "被拒", "碰壁", "落空", "没戏", "泡汤", "完了", "凉了"));
        words.put("anxious", List.of("焦虑", "紧张", "担心", "害怕", "不安", "惶恐", "压力", "喘不过气", "忐忑", "不安", "忧虑", "发愁", "纠结"));
        words.put("angry", List.of("生气", "愤怒", "恼火", "烦躁", "烦", "讨厌", "恨", "不爽", " rage", "发火", "暴躁"));
        words.put("excited", List.of("兴奋", "激动", "迫不及待", "振奋", "狂热"));
        words.put("fearful", List.of("害怕", "恐惧", "恐慌", "吓死", "不敢", "畏惧", "胆怯"));
        words.put("grateful", List.of("感谢", "谢谢", "感恩", "感激", "多亏", "幸好"));
        words.put("lonely", List.of("孤独", "寂寞", "孤单", "一个人", "没人陪", "冷落"));
        words.put("hopeful", List.of("希望", "相信", "会好的", "未来"));
        words.put("stressed", List.of("压力", "累", "疲惫", "受不了", "崩溃", "撑不住", "喘不过气", "加班", "考试", " deadline"));
        words.put("relieved", List.of("放心", "安心", "松了一口气", "还好", "总算", "解脱"));
        return words;
    }
}
