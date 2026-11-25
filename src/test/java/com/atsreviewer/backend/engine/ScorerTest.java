package com.atsreviewer.backend.engine;

import com.atsreviewer.backend.engine.model.JdSkill;
import com.atsreviewer.backend.engine.model.ParsedResume;
import com.atsreviewer.backend.engine.model.ScoreItem;
import com.atsreviewer.backend.engine.model.ScoreResult;
import com.atsreviewer.backend.engine.model.Section;
import com.atsreviewer.backend.engine.model.Status;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScorerTest {
    private final SkillMatcher fixtureMatcher = new SkillMatcher(Map.of(
            "java", 1,
            "python", 2,
            "aws", 3,
            "docker", 4
    ));

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

    @Test
    void addingAUsedSkillRaisesFixtureResumeScore() throws IOException {
        ParsedResume before = parsedFixture("resume-5.txt");
        ParsedResume after = withText(before, Section.EXPERIENCE,
                sectionText(before, Section.EXPERIENCE) + "\nBuilt Java services.");
        List<JdSkill> jdSkills = List.of(new JdSkill(1, "Java", true));

        ScoreResult beforeScore = new Scorer(fixtureMatcher).score(before, jdSkills);
        ScoreResult afterScore = new Scorer(fixtureMatcher).score(after, jdSkills);

        assertThat(beforeScore.score()).isLessThan(afterScore.score());
        assertThat(afterScore.score()).isEqualTo(100.0);
    }

    @Test
    void movingJavaFromExperienceToSkillsChangesUsedStatusToClaimed() throws IOException {
        ParsedResume used = new ParsedResume(
                List.of(),
                Map.of(Section.EXPERIENCE, "Built Java REST services."),
                List.of());
        ParsedResume claimed = new ParsedResume(
                List.of(),
                Map.of(Section.SKILLS, "Java"),
                List.of());
        List<JdSkill> jdSkills = List.of(new JdSkill(1, "Java", true));

        ScoreResult usedScore = new Scorer(fixtureMatcher).score(used, jdSkills);
        ScoreResult claimedScore = new Scorer(fixtureMatcher).score(claimed, jdSkills);

        assertThat(usedScore.score()).isEqualTo(100.0);
        assertThat(claimedScore.score()).isEqualTo(60.0);
        assertThat(usedScore.items().getFirst().status()).isEqualTo(Status.USED);
        assertThat(claimedScore.items().getFirst().status()).isEqualTo(Status.CLAIMED);
        assertThat(claimedScore.items().getFirst().foundIn()).isEqualTo(Section.SKILLS);
    }

    @Test
    void reorderingFixtureSectionsDoesNotChangeTheScore() throws IOException {
        ParsedResume normal = parsedFixture("resume-1.txt");
        Map<Section, String> reordered = new LinkedHashMap<>();
        reordered.put(Section.EDUCATION, sectionText(normal, Section.EDUCATION));
        reordered.put(Section.PROJECTS, sectionText(normal, Section.PROJECTS));
        reordered.put(Section.SKILLS, sectionText(normal, Section.SKILLS));
        reordered.put(Section.EXPERIENCE, sectionText(normal, Section.EXPERIENCE));
        reordered.put(Section.OTHER, sectionText(normal, Section.OTHER));
        ParsedResume reorderedResume = new ParsedResume(List.of(), reordered, List.of());
        List<JdSkill> jdSkills = List.of(
                new JdSkill(1, "Java", true),
                new JdSkill(4, "Docker", true));

        ScoreResult normalScore = new Scorer(fixtureMatcher).score(normal, jdSkills);
        ScoreResult reorderedScore = new Scorer(fixtureMatcher).score(reorderedResume, jdSkills);

        assertThat(reorderedScore.score()).isEqualTo(normalScore.score());
        assertThat(reorderedScore.items()).isEqualTo(normalScore.items());
    }

    private ParsedResume parsedFixture(String fileName) throws IOException {
        String text = readFixture(fileName);
        Map<Section, String> sections = new LinkedHashMap<>();
        Section current = Section.OTHER;
        StringBuilder currentText = new StringBuilder();

        for (String line : text.split("\\R")) {
            Section heading = headingFor(line);
            if (heading != null) {
                addSection(sections, current, currentText);
                current = heading;
                currentText = new StringBuilder();
            } else if (!line.isBlank()) {
                if (!currentText.isEmpty()) {
                    currentText.append('\n');
                }
                currentText.append(line);
            }
        }
        addSection(sections, current, currentText);
        return new ParsedResume(List.of(), sections, List.of());
    }

    private String readFixture(String fileName) throws IOException {
        try (InputStream stream = ScorerTest.class.getResourceAsStream("/fixtures/" + fileName)) {
            if (stream == null) {
                throw new IOException("Missing fixture: " + fileName);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Section headingFor(String line) {
        return switch (line.trim()) {
            case "EXPERIENCE" -> Section.EXPERIENCE;
            case "PROJECTS" -> Section.PROJECTS;
            case "SKILLS" -> Section.SKILLS;
            case "EDUCATION" -> Section.EDUCATION;
            default -> null;
        };
    }

    private void addSection(Map<Section, String> sections, Section section, StringBuilder text) {
        if (!text.isEmpty()) {
            sections.put(section, text.toString());
        }
    }

    private String sectionText(ParsedResume resume, Section section) {
        return resume.sections().getOrDefault(section, "");
    }

    private ParsedResume withText(ParsedResume resume, Section section, String text) {
        Map<Section, String> sections = new LinkedHashMap<>(resume.sections());
        sections.put(section, text);
        return new ParsedResume(resume.blocks(), sections, resume.warnings());
    }
}
