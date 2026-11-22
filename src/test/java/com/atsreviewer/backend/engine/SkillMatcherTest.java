package com.atsreviewer.backend.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillMatcherTest {
    @Test
    void matchesNodeJsAliases() {
        SkillMatcher matcher = new SkillMatcher(Map.of(
                "node.js", 1,
                "nodejs", 1,
                "postgresql", 2
        ));

        assertThat(matcher.findSkillIds("Built Node.js and PostgreSQL APIs"))
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void prefersLongestAliasMatch() {
        SkillMatcher matcher = new SkillMatcher(Map.of(
                "react", 1,
                "react native", 2
        ));

        assertThat(matcher.findSkillIds("Built React Native applications"))
                .containsExactly(2);
    }

    @Test
    void returnsNoMatchesForBlankText() {
        SkillMatcher matcher = new SkillMatcher(Map.of("java", 1));

        assertThat(matcher.findSkillIds("  ")).isEmpty();
    }

    @Test
    void rejectsNormalizedAliasesThatPointToDifferentSkills() {
        assertThatThrownBy(() -> new SkillMatcher(Map.of(
                "node.js", 1,
                "nodejs", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Alias maps to multiple skill IDs after normalization:");
    }
}
