package com.giftia.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MemoryAlgorithmsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rerankingUsesTheSpecifiedFiveFactorsAndLayerDecayStrength() {
        double now = 1_800_000_000;
        ObjectNode value = mapper.createObjectNode().put("content", "焦虑").put("emotion", "anxious")
                .put("importance", .8).put("created_at", now - 3600 * 24).put("last_accessed", 0).put("layer", 2);
        MemoryRecord memory = new MemoryRecord("m", value, now);
        double expected = .7 * .5 + Math.exp(-24.0 / 49) * .2 + .15 + .8 * .1 + .8 * .05;
        assertThat(MemoryAlgorithms.rerank(memory, .7, "anxious", now)).isCloseTo(expected, within(1e-12));
        memory.layer(1);
        double core = MemoryAlgorithms.rerank(memory, .7, "anxious", now);
        memory.layer(3);
        assertThat(core).isGreaterThan(MemoryAlgorithms.rerank(memory, .7, "anxious", now));
        assertThat(MemoryAlgorithms.rerank(memory, .7, "anxious", now))
                .isGreaterThan(MemoryAlgorithms.rerank(memory, .7, "neutral", now));
    }

    @Test
    void semanticCosineHandlesZeroInvalidAndDifferentDimensionVectors() {
        assertThat(MemoryAlgorithms.cosine(List.of(1.0, 0.0), List.of(1.0, 0.0))).isEqualTo(1);
        assertThat(MemoryAlgorithms.cosine(List.of(0.0, 0.0), List.of(1.0, 0.0))).isZero();
        assertThat(MemoryAlgorithms.cosine(List.of(1.0), List.of(1.0, 0.0))).isZero();
        assertThat(MemoryAlgorithms.cosine(List.of(Double.NaN), List.of(1.0))).isZero();
        assertThat(MemoryAlgorithms.cosine(null, List.of(1.0))).isZero();
    }

    @Test
    void chineseMatchingKeepsHanCharactersAndCombinesTwoToFourCharacterSubstrings() {
        MemoryRecord memory = new MemoryRecord("m", mapper.createObjectNode().put("content", "用户最近工作压力很大").put("importance", .5), 0);
        assertThat(MemoryAlgorithms.keywordScore(memory, "工作压力")).isEqualTo(.8);
        assertThat(MemoryAlgorithms.keywordScore(memory, "工作使我感到压力")).isGreaterThan(0);
        assertThat(MemoryAlgorithms.keywordScore(memory, "完全不相关")).isZero();
    }

    @Test
    void explicitNegativeContextOverridesPositiveWordsAndImportanceAssignsLayers() {
        assertThat(MemoryAlgorithms.emotion("我喜欢她，但她不喜欢我").name()).isEqualTo("sad");
        assertThat(MemoryAlgorithms.emotion("努力之后还是没找到工作").intensity()).isEqualTo(.6);
        assertThat(MemoryAlgorithms.automaticLayer(.5, .9)).isEqualTo(1);
        assertThat(MemoryAlgorithms.automaticLayer(.65, .5)).isEqualTo(2);
        assertThat(MemoryAlgorithms.automaticLayer(.3, .2)).isEqualTo(3);
        assertThat(MemoryAlgorithms.importance("我叫小明，职业是工程师，我喜欢音乐", .8, 3)).isBetween(.5, 1.0);
    }

    @Test
    void forgettingCurveUsesReviewCountsAndAge() {
        double now = 1_800_000_000;
        ObjectNode data = mapper.createObjectNode().put("content", "用户喜欢在周末阅读")
                .put("created_at", now - 86400).put("importance", .5).put("access_count", 0);
        MemoryRecord unreviewed = new MemoryRecord("a", data, now);
        data.put("access_count", 10);
        MemoryRecord reviewed = new MemoryRecord("b", data, now);
        assertThat(MemoryAlgorithms.retention(reviewed, now)).isGreaterThan(MemoryAlgorithms.retention(unreviewed, now));
        assertThat(MemoryAlgorithms.retention(unreviewed, now)).isCloseTo(Math.exp(-24.0 / (.55 * 24 + 1)), within(1e-12));
    }

    @Test
    void filtersIncompleteDuplicateFactsWithoutDroppingExplicitAiAttribution() {
        assertThat(MemoryAlgorithms.filterFacts(List.of("[关于用户] 我喜欢书店", "[关于用户] 我喜欢书店",
                "短", "尚未完成的事实，", "[AI互动] AI曾推荐歌曲《晴天》")))
                .containsExactly("[关于用户] 我喜欢书店", "[AI互动] AI曾推荐歌曲《晴天》");
        assertThat(MemoryAlgorithms.ruleFacts("你可以说我是勇敢的人，我喜欢书店"))
                .containsExactly("[关于用户] 我喜欢书店");
    }
}
