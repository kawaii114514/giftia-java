package com.giftia.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotenvLoaderTest {
    @TempDir Path temporary;

    @Test void loadsOnlyTemporaryConfigurationWithoutShellEvaluation() throws Exception {
        Path config = temporary.resolve("test.env");
        Files.writeString(config, "\uFEFFCHAT_API_KEY=unit-test-key\n"
                + "export EMBEDDING_API_KEY='unit-embedding'\n"
                + "NAME=\"中文值 # 保留\" # comment\n"
                + "PLAIN=value # ignored\n"
                + "LITERAL='$(never-run) `literal`'\n"
                + "EMPTY=\n# IGNORE=value\nINVALID-NAME=ignored\n", StandardCharsets.UTF_8);
        assertThat(DotenvLoader.read(config)).containsEntry("CHAT_API_KEY", "unit-test-key")
                .containsEntry("EMBEDDING_API_KEY", "unit-embedding").containsEntry("NAME", "中文值 # 保留")
                .containsEntry("PLAIN", "value").containsEntry("LITERAL", "$(never-run) `literal`")
                .containsEntry("EMPTY", "").doesNotContainKeys("IGNORE", "INVALID-NAME");
        assertThat(DotenvLoader.read(temporary.resolve("missing.env"))).isEmpty();
    }

    @Test void explicitKeysOverrideProviderKeysAndLegacySpellingsRemainSupported() {
        var explicit = new GiftiaProperties(new org.springframework.mock.env.MockEnvironment()
                .withProperty("CHAT_API_KEY", "direct-chat").withProperty("DEEPSEEK_API_KEY", "provider-chat")
                .withProperty("EMBEDDING_API_KEY", "direct-embedding").withProperty("SILICONFLOW_API_KEY", "provider-embedding"));
        assertThat(explicit.chatKey()).isEqualTo("direct-chat");
        assertThat(explicit.embeddingKey()).isEqualTo("direct-embedding");
        var legacy = new GiftiaProperties(new org.springframework.mock.env.MockEnvironment()
                .withProperty("Deepseek_API_KEY", "legacy-chat").withProperty("ZhipuAI_API_KEY", "legacy-embedding"));
        legacy.setEmbeddingBaseUrl("https://open.bigmodel.cn/api/paas/v4/embeddings");
        assertThat(legacy.chatKey()).isEqualTo("legacy-chat");
        assertThat(legacy.embeddingKey()).isEqualTo("legacy-embedding");
    }
}
