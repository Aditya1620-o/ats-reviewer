package com.atsreviewer.backend.engine;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SkillMatcher {
    private final Map<String, Integer> aliases;

    public SkillMatcher(Map<String, Integer> aliases) {
        Objects.requireNonNull(aliases, "aliases cannot be null");

        java.util.Map<String, Integer> normalizedAliases = new java.util.HashMap<>();
        aliases.forEach((alias, skillId) -> {
            if (alias != null && skillId != null) {
                normalizedAliases.put(normalizeAlias(alias), skillId);
            }
        });
        this.aliases = Map.copyOf(normalizedAliases);
    }

    public Set<Integer> findSkillIds(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        List<String> words = List.of(normalizeText(text).split("\\s+"));
        Set<Integer> matches = new LinkedHashSet<>();
        Set<Integer> consumedIndexes = new LinkedHashSet<>();

        for (int length = 4; length >= 1; length--) {
            for (int start = 0; start + length <= words.size(); start++) {
                boolean alreadyConsumed = false;
                for (int index = start; index < start + length; index++) {
                    if (consumedIndexes.contains(index)) {
                        alreadyConsumed = true;
                        break;
                    }
                }
                if (alreadyConsumed) {
                    continue;
                }

                String phrase = String.join(" ", words.subList(start, start + length));
                Integer skillId = aliases.get(phrase);
                if (skillId != null) {
                    matches.add(skillId);
                    for (int index = start; index < start + length; index++) {
                        consumedIndexes.add(index);
                    }
                }
            }
        }

        return Set.copyOf(matches);
    }

    private String normalizeText(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        normalized = normalized.replace("node.js", "node dot js");
        normalized = normalized.replace("nodejs", "node dot js");
        normalized = normalized.replace("c++", "c plus plus");
        normalized = normalized.replace("c#", "c sharp");
        normalized = normalized.replace("ci/cd", "ci cd");
        normalized = normalized.replace('-', ' ');
        normalized = normalized.replace('/', ' ');
        normalized = normalized.replaceAll("[^a-z0-9+#. ]", " ");
        normalized = normalized.replace('.', ' ');
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private static String normalizeAlias(String alias) {
        String normalized = alias.toLowerCase(Locale.ROOT);
        normalized = normalized.replace("node.js", "node dot js");
        normalized = normalized.replace("nodejs", "node dot js");
        normalized = normalized.replace("c++", "c plus plus");
        normalized = normalized.replace("c#", "c sharp");
        normalized = normalized.replace("ci/cd", "ci cd");
        normalized = normalized.replace('-', ' ').replace('/', ' ');
        normalized = normalized.replaceAll("[^a-z0-9+#. ]", " ");
        normalized = normalized.replace('.', ' ');
        return normalized.replaceAll("\\s+", " ").trim();
    }
}
