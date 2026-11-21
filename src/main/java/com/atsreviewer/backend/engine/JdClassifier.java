package com.atsreviewer.backend.engine;

import com.atsreviewer.backend.engine.model.JdSkill;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class JdClassifier {
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?;])\\s+|\\R+");
    private static final Pattern REQUIRED_CUE = Pattern.compile(
            "\\b(?:must|required|mandatory)\\b|\\bstrong\\s+knowledge\\s+of\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFERRED_CUE = Pattern.compile(
            "\\bgood\\s+to\\s+have\\b|\\bpreferred\\b|\\bplus\\b|\\bbonus\\b",
            Pattern.CASE_INSENSITIVE);

    private final SkillMatcher matcher;
    private final Map<Integer, String> skillNames;

    public JdClassifier(SkillMatcher matcher, Map<Integer, String> skillNames) {
        this.matcher = Objects.requireNonNull(matcher, "matcher cannot be null");
        this.skillNames = Map.copyOf(
                Objects.requireNonNull(skillNames, "skillNames cannot be null"));
        this.skillNames.forEach((skillId, name) -> {
            if (name.isBlank()) {
                throw new IllegalArgumentException("Canonical skill names cannot be blank");
            }
        });
    }

    public List<JdSkill> classify(String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) {
            throw new IllegalArgumentException("Job description text is required");
        }

        Map<Integer, Boolean> requiredBySkillId = new TreeMap<>();
        for (String sentence : SENTENCE_BOUNDARY.split(jobDescription)) {
            boolean required = REQUIRED_CUE.matcher(sentence).find()
                    || !PREFERRED_CUE.matcher(sentence).find();
            for (int skillId : matcher.findSkillIds(sentence)) {
                requiredBySkillId.merge(skillId, required, Boolean::logicalOr);
            }
        }

        return requiredBySkillId.entrySet().stream()
                .map(entry -> new JdSkill(
                        entry.getKey(),
                        skillName(entry.getKey()),
                        entry.getValue()))
                .toList();
    }

    private String skillName(int skillId) {
        String name = skillNames.get(skillId);
        if (name == null) {
            throw new IllegalStateException("Missing canonical name for skill id: " + skillId);
        }
        return name;
    }
}
