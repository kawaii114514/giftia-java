package com.giftia.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.giftia.config.GiftiaProperties;
import com.giftia.web.ApiTelemetry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** OpenAI-compatible model access through Spring AI; no Python runtime or subprocess. */
@Component
public class SpringAiModelGateway implements ModelGateway {
    private final GiftiaProperties properties;
    private final OpenAiChatModel chat;
    private final OpenAiEmbeddingModel embeddings;
    private final RestClient healthClient;
    private final Endpoint chatEndpoint;
    private final Map<String, Object> presets;
    private final List<String> multimodalKeywords;
    private final ApiTelemetry telemetry;

    public SpringAiModelGateway(GiftiaProperties properties, ObjectMapper mapper, ApiTelemetry telemetry) throws IOException {
        this.properties = properties;
        this.telemetry = telemetry;
        chatEndpoint = endpoint(properties.getChatBaseUrl(), "chat/completions");
        Endpoint embeddingEndpoint = endpoint(properties.getEmbeddingBaseUrl(), "embeddings");
        RetryTemplate retry = RetryTemplate.builder().maxAttempts(1).build();
        chat = OpenAiChatModel.builder().openAiApi(api(chatEndpoint, properties.chatKey(), true))
                .defaultOptions(OpenAiChatOptions.builder().model(properties.getChatModel()).build())
                .retryTemplate(retry).build();
        embeddings = new OpenAiEmbeddingModel(api(embeddingEndpoint, properties.embeddingKey(), false),
                MetadataMode.EMBED, OpenAiEmbeddingOptions.builder().model(properties.getEmbeddingModel())
                .encodingFormat("float").build(), retry);
        healthClient = restClient(Duration.ofSeconds(5)).baseUrl(chatEndpoint.origin()).build();
        try (var input = new ClassPathResource("model-presets.json").getInputStream()) {
            presets = mapper.readValue(input, new TypeReference<Map<String, Object>>() {});
        }
        try (var input = new ClassPathResource("multimodal-models.json").getInputStream()) {
            List<String> keywords = mapper.readValue(input, new TypeReference<List<String>>() {});
            multimodalKeywords = keywords.stream().map(keyword -> keyword.toLowerCase(Locale.ROOT)).toList();
        }
    }

    @Override
    public String complete(String system, String user, double temperature) {
        long started = System.nanoTime();
        try { return completeText(system, user, temperature); }
        finally { telemetry.recordModelCall(System.nanoTime() - started); }
    }

    private String completeText(String system, String user, double temperature) {
        requireChatKey();
        var response = chat.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user)),
                options(temperature, false)));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null)
            throw new IllegalStateException("模型返回空响应");
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) throw new IllegalStateException("模型未返回文本");
        return text;
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        long started = System.nanoTime();
        try { return embedTexts(texts); }
        finally { telemetry.recordModelCall(System.nanoTime() - started); }
    }

    private List<List<Double>> embedTexts(List<String> texts) {
        if (properties.embeddingKey().isBlank()) throw new IllegalStateException("未配置嵌入模型密钥");
        var response = embeddings.call(new EmbeddingRequest(texts,
                OpenAiEmbeddingOptions.builder().model(properties.getEmbeddingModel()).encodingFormat("float").build()));
        if (response == null || response.getResults().size() != texts.size())
            throw new IllegalStateException("嵌入模型返回的向量数量不匹配");
        List<Embedding> vectors = new ArrayList<>(response.getResults());
        vectors.sort(Comparator.comparingInt(Embedding::getIndex));
        List<List<Double>> result = new ArrayList<>();
        for (int index = 0; index < vectors.size(); index++) {
            Embedding vector = vectors.get(index);
            if (vector.getIndex() != index || vector.getOutput() == null || vector.getOutput().length == 0)
                throw new IllegalStateException("嵌入模型返回无效向量");
            List<Double> values = new ArrayList<>();
            for (float value : vector.getOutput()) {
                if (!Float.isFinite(value)) throw new IllegalStateException("嵌入向量包含无效数字");
                values.add((double) value);
            }
            result.add(values);
        }
        return result;
    }

    @Override
    public void stream(List<ModelMessage> messages, Consumer<String> onToken) {
        long started = System.nanoTime();
        try { streamTokens(messages, onToken); }
        finally { telemetry.recordModelCall(System.nanoTime() - started); }
    }

    private void streamTokens(List<ModelMessage> messages, Consumer<String> onToken) {
        requireChatKey();
        List<Message> prompt = new ArrayList<>();
        for (ModelMessage message : messages) {
            switch (message.role()) {
                case "system" -> prompt.add(new SystemMessage(message.content()));
                case "assistant" -> prompt.add(new AssistantMessage(message.content()));
                case "user" -> {
                    if (message.imageData() != null && !message.imageData().isBlank()) {
                        if (!multimodal()) throw new IllegalArgumentException("当前模型不支持图片输入");
                        prompt.add(UserMessage.builder().text(message.content()).media(image(message.imageData())).build());
                    } else prompt.add(new UserMessage(message.content()));
                }
                default -> throw new IllegalArgumentException("无效消息角色");
            }
        }
        AtomicBoolean finished = new AtomicBoolean();
        chat.stream(new Prompt(prompt, options(0.8, properties.isThinkingEnabled())))
                .doOnNext(response -> {
                    if (response.getResult() != null && response.getResult().getOutput() != null) {
                        String reason = response.getResult().getMetadata().getFinishReason();
                        if (reason != null && !reason.isBlank()) {
                            if (!reason.equalsIgnoreCase("STOP"))
                                throw new IllegalStateException("模型未正常完成文本回复");
                            finished.set(true);
                        }
                        String token = response.getResult().getOutput().getText();
                        if (token != null && !token.isEmpty()) onToken.accept(token);
                    }
                }).blockLast(timeout());
        if (!finished.get()) throw new IllegalStateException("模型流在完成前中断");
    }

    @Override
    public Map<String, Object> info() {
        return Map.of("model", properties.getChatModel(), "base_url", properties.getChatBaseUrl(), "multimodal", multimodal());
    }
    @Override public Map<String, Object> presets() { return presets; }
    @Override public boolean configured() { return !properties.chatKey().isBlank(); }
    @Override public boolean healthy() {
        if (!configured()) return false;
        try {
            String path = chatEndpoint.path().substring(0, chatEndpoint.path().length() - "chat/completions".length()) + "models";
            healthClient.get().uri(path).headers(headers -> headers.setBearerAuth(properties.chatKey()))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (Exception failure) { return false; }
    }

    private boolean multimodal() {
        if (properties.getChatMultimodal().equals("true")) return true;
        if (properties.getChatMultimodal().equals("false")) return false;
        String model = properties.getChatModel().toLowerCase(Locale.ROOT);
        return multimodalKeywords.stream().anyMatch(model::contains);
    }
    private void requireChatKey() {
        if (!configured()) throw new IllegalStateException("未配置对话模型密钥");
    }
    private Duration timeout() { return Duration.ofSeconds(Math.max(1, properties.getModelTimeoutSeconds())); }
    private OpenAiChatOptions options(double temperature, boolean thinking) {
        var builder = OpenAiChatOptions.builder().model(properties.getChatModel()).temperature(temperature);
        String model = properties.getChatModel().toLowerCase(Locale.ROOT);
        boolean deepseek = model.startsWith("deepseek");
        boolean glm = model.startsWith("glm-4.6") || model.startsWith("glm-5.1");
        boolean qwen = model.startsWith("qwen");
        if (deepseek || glm) {
            builder.extraBody(Map.of("thinking", Map.of("type", thinking ? "enabled" : "disabled")));
            if (thinking && deepseek) builder.reasoningEffort("high");
        } else if (qwen) {
            builder.extraBody(thinking ? Map.of("enable_thinking", true, "return_reasoning", true)
                    : Map.of("enable_thinking", false));
        }
        if (thinking && (deepseek || glm || qwen)) builder.temperature(1.0);
        return builder.build();
    }
    private OpenAiApi api(Endpoint endpoint, String key, boolean isChat) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        var builder = OpenAiApi.builder().apiKey(key.isBlank() ? "not-configured" : key)
                .baseUrl(endpoint.origin()).restClientBuilder(restClient(timeout()))
                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(http)));
        if (isChat) builder.completionsPath(endpoint.path());
        else builder.embeddingsPath(endpoint.path());
        return builder.build();
    }
    private RestClient.Builder restClient(Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }
    static Endpoint endpoint(String address, String operation) {
        URI uri = URI.create(address.strip());
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("模型接口地址无效");
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        if (path.isEmpty()) path = "/v1";
        if (!path.endsWith("/" + operation)) path += "/" + operation;
        return new Endpoint(origin, path);
    }
    private static Media image(String data) {
        String mime = "image/jpeg";
        String encoded = data;
        if (data.startsWith("data:")) {
            int comma = data.indexOf(',');
            if (comma < 0 || !data.substring(0, comma).matches("data:image/(png|jpeg|webp|gif);base64"))
                throw new IllegalArgumentException("图片格式无效");
            mime = data.substring(5, data.indexOf(';'));
            encoded = data.substring(comma + 1);
        }
        if (encoded.length() > 14_000_000) throw new IllegalArgumentException("图片过大");
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) throw new IllegalArgumentException("图片大小无效");
        return new Media(MimeTypeUtils.parseMimeType(mime), new ByteArrayResource(bytes));
    }
    record Endpoint(String origin, String path) {}
}
