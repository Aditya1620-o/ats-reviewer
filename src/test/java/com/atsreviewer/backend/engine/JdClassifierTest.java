package com.atsreviewer.backend.engine;

import com.atsreviewer.backend.engine.model.JdSkill;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdClassifierTest {
    @Test
    void classifiesRequiredAndPreferredSkillsFromJobDescriptionFixture() throws IOException {
        List<JdSkill> skills = classifier().classify(
                readFixture("/fixtures/job-descriptions/jd-01-java-backend.txt"));

        assertThat(skills).containsExactly(
                new JdSkill(1, "Java", true),
                new JdSkill(3, "AWS", false),
                new JdSkill(4, "Docker", false));
    }

    @Test
    void classifiesSkillsFromRequiredAndPreferredBullets() {
        JdClassifier classifier = classifier();

        List<JdSkill> skills = classifier.classify("""
                Required: Java and Docker.
                Python is preferred.
                AWS is a bonus.
                """);

        assertThat(skills).containsExactly(
                new JdSkill(1, "Java", true),
                new JdSkill(2, "Python", false),
                new JdSkill(3, "AWS", false),
                new JdSkill(4, "Docker", true));
    }

    @Test
    void treatsUncuedSkillsAsRequiredAndRequiredMentionOverridesPreferred() {
        JdClassifier classifier = classifier();

        List<JdSkill> skills = classifier.classify("""
                Java is a plus.
                Java experience is required.
                Python development experience.
                """);

        assertThat(skills).containsExactly(
                new JdSkill(1, "Java", true),
                new JdSkill(2, "Python", true));
    }

    @Test
    void recognizesDocumentedCuePhrases() {
        List<JdSkill> skills = classifier().classify("""
                Must have Java.
                Python is mandatory.
                Strong knowledge of AWS is required.
                Docker is good to have.
                """);

        assertThat(skills).containsExactly(
                new JdSkill(1, "Java", true),
                new JdSkill(2, "Python", true),
                new JdSkill(3, "AWS", true),
                new JdSkill(4, "Docker", false));
    }

    @Test
    void doesNotTreatPlusSymbolsInsideSkillNamesAsPreferredCue() {
        JdClassifier classifier = new JdClassifier(
                new SkillMatcher(Map.of("c++", 5)),
                Map.of(5, "C++"));

        assertThat(classifier.classify("C++ development experience."))
                .containsExactly(new JdSkill(5, "C++", true));
    }

    @Test
    void rejectsBlankDescription() {
        assertThatThrownBy(() -> classifier().classify("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Job description text is required");
    }

    @Test
    void rejectsMatchedSkillWithoutCanonicalName() {
        JdClassifier classifier = new JdClassifier(
                new SkillMatcher(Map.of("java", 99)),
                Map.of());

        assertThatThrownBy(() -> classifier.classify("Java experience"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing canonical name for skill id: 99");
    }

    private JdClassifier classifier() {
        return new JdClassifier(
                new SkillMatcher(Map.of(
                        "java", 1,
                        "python", 2,
                        "aws", 3,
                        "docker", 4)),
                Map.of(
                        1, "Java",
                        2, "Python",
                        3, "AWS",
                        4, "Docker"));
    }

    private String readFixture(String resourcePath) throws IOException {
        try (InputStream input = JdClassifierTest.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing test fixture: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
