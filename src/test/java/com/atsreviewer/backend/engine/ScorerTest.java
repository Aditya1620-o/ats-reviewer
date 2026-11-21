package com.atsreviewer.backend.engine;

import com.atsreviewer.backend.engine.model.JdSkill;
import com.atsreviewer.backend.engine.model.ParsedResume;
import com.atsreviewer.backend.engine.model.ScoreItem;
import com.atsreviewer.backend.engine.model.ScoreResult;
import com.atsreviewer.backend.engine.model.Section;
import com.atsreviewer.backend.engine.model.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScorerTest {
    @Test
    void calculatesUsedClaimedAndMissingSkills() {
        SkillMatcher matcher = new SkillMatcher(Map.of(
                "java", 1,
                "docker", 2,
                "aws", 3
        ));

        ParsedResume resume = new ParsedResume(
                List.of(),
                Map.of(
                        Section.PROJECTS, "Built a Java service",
                        Section.SKILLS, "Docker"
                ),
                List.of()
        );

        List<JdSkill> jdSkills = List.of(
                new JdSkill(1, "Java", true),
                new JdSkill(2, "Docker", true),
                new JdSkill(3, "AWS", false)
        );

        ScoreResult result = new Scorer(matcher).score(resume, jdSkills);

        assertThat(result.score()).isEqualTo(68.57);
        assertThat(result.engineVersion()).isEqualTo("1.0.0");
        assertThat(result.items()).extracting(ScoreItem::name)
                .containsExactly("Docker", "Java", "AWS");
        assertThat(result.items()).extracting(ScoreItem::status)
                .containsExactly(Status.CLAIMED, Status.USED, Status.MISSING);
    }

    @Test
    void returnsZeroWhenThereAreNoJdSkills() {
        SkillMatcher matcher = new SkillMatcher(Map.of("java", 1));
        ParsedResume resume = new ParsedResume(
                List.of(),
                Map.of(Section.PROJECTS, "Java project"),
                List.of()
        );

        ScoreResult result = new Scorer(matcher).score(resume, List.of());

        assertThat(result.score()).isZero();
        assertThat(result.items()).isEmpty();
    }
}
