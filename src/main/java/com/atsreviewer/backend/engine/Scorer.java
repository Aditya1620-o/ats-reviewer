package com.atsreviewer.backend.engine;

import com.atsreviewer.backend.engine.model.JdSkill;
import com.atsreviewer.backend.engine.model.ParsedResume;
import com.atsreviewer.backend.engine.model.ScoreItem;
import com.atsreviewer.backend.engine.model.ScoreResult;
import com.atsreviewer.backend.engine.model.Section;
import com.atsreviewer.backend.engine.model.Status;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Scorer {
    public static final String ENGINE_VERSION = "1.0.0";

    private static final double REQUIRED_WEIGHT = 3.0;
    private static final double PREFERRED_WEIGHT = 1.0;

    private static final Map<Status, Double> STRENGTH = Map.of(
            Status.USED, 1.0,
            Status.CLAIMED, 0.6,
            Status.MISSING, 0.0
    );

    private final SkillMatcher matcher;

    public Scorer(SkillMatcher matcher) {
        this.matcher = Objects.requireNonNull(matcher, "matcher cannot be null");
    }

    public ScoreResult score(ParsedResume resume, List<JdSkill> jdSkills) {
        Objects.requireNonNull(resume, "resume cannot be null");
        Objects.requireNonNull(jdSkills, "jdSkills cannot be null");

        Map<Section, Set<Integer>> found = new EnumMap<>(Section.class);
        resume.sections().forEach((section, text) ->
                found.put(section, matcher.findSkillIds(text)));

        List<ScoreItem> items = jdSkills.stream()
                .map(skill -> toItem(skill, found))
                .toList();

        double possible = items.stream().mapToDouble(ScoreItem::weight).sum();
        double earned = items.stream().mapToDouble(ScoreItem::points).sum();
        double score = possible == 0 ? 0 : roundTwoPlaces(100 * earned / possible);

        return new ScoreResult(score, items, ENGINE_VERSION);
    }

    private ScoreItem toItem(JdSkill skill, Map<Section, Set<Integer>> found) {
        Section usedIn = firstSectionContaining(
                List.of(Section.EXPERIENCE, Section.PROJECTS), skill.skillId(), found);
        Section anyIn = usedIn != null
                ? usedIn
                : firstSectionContaining(List.of(Section.values()), skill.skillId(), found);

        Status status;
        if (usedIn != null) {
            status = Status.USED;
        } else if (anyIn != null) {
            status = Status.CLAIMED;
        } else {
            status = Status.MISSING;
        }

        double weight = skill.required() ? REQUIRED_WEIGHT : PREFERRED_WEIGHT;
        double points = roundTwoPlaces(weight * STRENGTH.get(status));

        return new ScoreItem(skill.skillId(), skill.name(), weight, status, anyIn, points);
    }

    private Section firstSectionContaining(
            List<Section> sectionOrder,
            int skillId,
            Map<Section, Set<Integer>> found) {
        return sectionOrder.stream()
                .filter(section -> found.getOrDefault(section, Set.of()).contains(skillId))
                .findFirst()
                .orElse(null);
    }

    private static double roundTwoPlaces(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
