package com.giftia.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "giftia")
@Component
public class GiftiaProperties {
    private final Environment environment;
    private Path dataDir = WorkspacePaths.root().resolve("backend");
    private Path database;
    private String chatModel = "deepseek-v4-flash";
    private String chatBaseUrl = "https://api.deepseek.com/v1";
    private String chatMultimodal = "auto";
    private String embeddingModel = "BAAI/bge-m3";
    private String embeddingBaseUrl = "https://api.siliconflow.cn/v1/embeddings";
    private String accessKey = "";
    private String adminKey = "";
    private boolean thinkingEnabled = true;
    private int modelTimeoutSeconds = 90;
    private int analysisTimeoutSeconds = 65;

    public GiftiaProperties(Environment environment) { this.environment = environment; }
    public Path getDataDir() { return dataDir.toAbsolutePath().normalize(); }
    public void setDataDir(Path value) { dataDir = value; }
    public Path getDatabase() {
        String override = environment.getProperty("GIFTIA_DATABASE");
        return (database != null ? database : override != null && !override.isBlank() ? Path.of(override) : getDataDir().resolve("giftia.db")).toAbsolutePath().normalize();
    }
    public void setDatabase(Path value) { database = value; }
    public String getChatModel() { return chatModel; }
    public void setChatModel(String value) { chatModel = value; }
    public String getChatBaseUrl() { return chatBaseUrl; }
    public void setChatBaseUrl(String value) { chatBaseUrl = value; }
    public String getChatMultimodal() { return chatMultimodal; }
    public void setChatMultimodal(String value) {
        String mode = value == null ? "auto" : value.strip().toLowerCase(Locale.ROOT);
        if (!List.of("auto", "true", "false").contains(mode))
            throw new IllegalArgumentException("CHAT_MULTIMODAL 必须为 auto、true 或 false");
        chatMultimodal = mode;
    }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String value) { embeddingModel = value; }
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String value) { embeddingBaseUrl = value; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String value) { accessKey = value; }
    public String getAdminKey() { return adminKey; }
    public void setAdminKey(String value) { adminKey = value; }
    public List<String> getCorsOrigins() {
        return Arrays.stream(environment.getProperty("CORS_ORIGINS", "http://localhost:3000,http://127.0.0.1:3000").split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
    public boolean isThinkingEnabled() { return thinkingEnabled; }
    public void setThinkingEnabled(boolean value) { thinkingEnabled = value; }
    public int getModelTimeoutSeconds() { return modelTimeoutSeconds; }
    public void setModelTimeoutSeconds(int value) { modelTimeoutSeconds = value; }
    public int getAnalysisTimeoutSeconds() { return analysisTimeoutSeconds; }
    public void setAnalysisTimeoutSeconds(int value) { analysisTimeoutSeconds = value; }

    public String chatKey() {
        String direct = environment.getProperty("CHAT_API_KEY", "");
        if (!direct.isBlank()) return direct;
        String endpoint = chatBaseUrl.toLowerCase();
        if (endpoint.contains("siliconflow")) return key("SILICONFLOW_API_KEY");
        if (endpoint.contains("bigmodel")) return key("ZHIPU_API_KEY", "ZhipuAI_API_KEY");
        if (endpoint.contains("dashscope")) return key("DASHSCOPE_API_KEY");
        if (endpoint.contains("deepseek") || chatModel.toLowerCase().startsWith("deepseek")) return key("DEEPSEEK_API_KEY", "Deepseek_API_KEY");
        return key("OPENAI_API_KEY");
    }
    public String embeddingKey() {
        String direct = key("EMBEDDING_API_KEY");
        if (!direct.isBlank()) return direct;
        String endpoint = embeddingBaseUrl.toLowerCase();
        if (endpoint.contains("siliconflow")) return key("SILICONFLOW_API_KEY");
        if (endpoint.contains("bigmodel")) return key("ZHIPU_API_KEY", "ZhipuAI_API_KEY");
        return key("OPENAI_API_KEY");
    }
    private String key(String... names) {
        for (String name : names) { String value = environment.getProperty(name, ""); if (!value.isBlank()) return value; }
        return "";
    }
}
