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
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScorerTest {
    private final SkillMatcher fixtureMatcher = new SkillMatcher(Map.ofEntries(
            Map.entry("java", 1),
            Map.entry("python", 2),
            Map.entry("aws", 3),
            Map.entry("docker", 4),
            Map.entry("spring boot", 5),
            Map.entry("rest", 6),
            Map.entry("postgresql", 7),
            Map.entry("junit", 8),
            Map.entry("kafka", 9),
            Map.entry("ci/cd", 10),
            Map.entry("sql", 11),
            Map.entry("apache spark", 12),
            Map.entry("apache airflow", 13),
            Map.entry("snowflake", 14),
            Map.entry("etl", 15),
            Map.entry("data modeling", 16),
            Map.entry("typescript", 17),
            Map.entry("react", 18),
            Map.entry("html", 19),
            Map.entry("css", 20),
            Map.entry("accessibility", 21),
            Map.entry("node.js", 22),
            Map.entry("playwright", 23),
            Map.entry("performance profiling", 24)
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
                .containsExactly(Status.USED, Status.CLAIMED, Status.MISSING);
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
    void scoresJavaBackendFixturesUsingSkillsInTheirResumeSections() throws IOException {
        ParsedResume resume = parsedFixture("resumes/resume-01-java-backend.txt");
        String jobDescription = readFixture("job-descriptions/jd-01-java-backend.txt");
        JdClassifier classifier = new JdClassifier(
                fixtureMatcher,
                Map.of(
                        1, "Java",
                        2, "Python",
                        3, "AWS",
                        4, "Docker",
                        5, "Spring Boot",
                        6, "REST",
                        7, "PostgreSQL",
                        8, "JUnit",
                        9, "Kafka",
                        10, "CI/CD"));
        List<JdSkill> jdSkills = classifier.classify(jobDescription);

        ScoreResult result = new Scorer(fixtureMatcher).score(resume, jdSkills);

        assertThat(result.items()).extracting(ScoreItem::name)
                .containsExactly("Java", "AWS", "Docker", "Spring Boot", "REST",
                        "PostgreSQL", "JUnit", "Kafka", "CI/CD");
        assertThat(result.items()).extracting(ScoreItem::status)
                .containsExactly(
                        Status.USED, Status.USED, Status.USED, Status.USED, Status.USED,
                        Status.USED, Status.USED, Status.USED, Status.MISSING);
        assertThat(result.items()).extracting(ScoreItem::foundIn)
                .containsExactly(
                        Section.EXPERIENCE, Section.EXPERIENCE, Section.EXPERIENCE,
                        Section.EXPERIENCE, Section.EXPERIENCE, Section.EXPERIENCE,
                        Section.EXPERIENCE, Section.PROJECTS, null);
        assertThat(result.score()).isEqualTo(94.74);
    }

    @Test
    void scoresDataEngineeringFixturesUsingSkillsInTheirResumeSections() throws IOException {
        ParsedResume resume = parsedFixture("resumes/resume-02-data-engineering.txt");
        String jobDescription = readFixture("job-descriptions/jd-02-data-engineering.txt");

        ScoreResult result = new Scorer(fixtureMatcher).score(
                resume, fixtureClassifier().classify(jobDescription));

        assertThat(result.items()).extracting(ScoreItem::name)
                .containsExactly("Python", "AWS", "SQL", "Apache Spark", "Apache Airflow",
                        "Snowflake", "ETL", "Data modeling");
        assertThat(result.items()).extracting(ScoreItem::status)
                .containsExactly(
                        Status.USED, Status.USED, Status.USED, Status.USED,
                        Status.USED, Status.USED, Status.CLAIMED, Status.MISSING);
        assertThat(result.items()).extracting(ScoreItem::foundIn)
                .containsExactly(
                        Section.EXPERIENCE, Section.EXPERIENCE, Section.EXPERIENCE,
                        Section.EXPERIENCE, Section.EXPERIENCE, Section.PROJECTS,
                        Section.SKILLS, null);
        assertThat(result.score()).isEqualTo(91.25);
    }

    @Test
    void scoresFrontendFixturesUsingSkillsInTheirResumeSections() throws IOException {
        ParsedResume resume = parsedFixture("resumes/resume-03-frontend.txt");
        String jobDescription = readFixture("job-descriptions/jd-03-frontend.txt");

        ScoreResult result = new Scorer(fixtureMatcher).score(
                resume, fixtureClassifier().classify(jobDescription));

        assertThat(result.items()).extracting(ScoreItem::name)
                .containsExactly("CI/CD", "TypeScript", "React", "HTML", "CSS",
                        "Accessibility", "Node.js", "Playwright", "Performance profiling");
        assertThat(result.items()).extracting(ScoreItem::status)
                .containsExactly(
                        Status.MISSING, Status.USED, Status.USED, Status.CLAIMED,
                        Status.CLAIMED, Status.USED, Status.USED, Status.USED,
                        Status.MISSING);
        assertThat(result.items()).extracting(ScoreItem::foundIn)
                .containsExactly(
                        null, Section.EXPERIENCE, Section.EXPERIENCE, Section.SKILLS,
                        Section.SKILLS, Section.EXPERIENCE, Section.PROJECTS,
                        Section.EXPERIENCE, null);
        assertThat(result.score()).isEqualTo(76.84);
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

    private JdClassifier fixtureClassifier() {
        return new JdClassifier(
                fixtureMatcher,
                Map.ofEntries(
                        Map.entry(1, "Java"),
                        Map.entry(2, "Python"),
                        Map.entry(3, "AWS"),
                        Map.entry(4, "Docker"),
                        Map.entry(5, "Spring Boot"),
                        Map.entry(6, "REST"),
                        Map.entry(7, "PostgreSQL"),
                        Map.entry(8, "JUnit"),
                        Map.entry(9, "Kafka"),
                        Map.entry(10, "CI/CD"),
                        Map.entry(11, "SQL"),
                        Map.entry(12, "Apache Spark"),
                        Map.entry(13, "Apache Airflow"),
                        Map.entry(14, "Snowflake"),
                        Map.entry(15, "ETL"),
                        Map.entry(16, "Data modeling"),
                        Map.entry(17, "TypeScript"),
                        Map.entry(18, "React"),
                        Map.entry(19, "HTML"),
                        Map.entry(20, "CSS"),
                        Map.entry(21, "Accessibility"),
                        Map.entry(22, "Node.js"),
                        Map.entry(23, "Playwright"),
                        Map.entry(24, "Performance profiling")));
    }

    private Section headingFor(String line) {
        return switch (line.trim().toUpperCase(Locale.ROOT)) {
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
