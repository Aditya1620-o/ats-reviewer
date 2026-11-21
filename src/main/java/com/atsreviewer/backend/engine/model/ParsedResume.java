package com.atsreviewer.backend.engine.model;

import java.util.List;
import java.util.Map;

public record ParsedResume(
        List<TextBlock> blocks,
        Map<Section, String> sections,
        List<String> warnings
) {
    public ParsedResume {
        blocks = List.copyOf(blocks);
        sections = Map.copyOf(sections);
        warnings = List.copyOf(warnings);
    }

    public boolean hasText() {
        return sections.values().stream().anyMatch(text -> text != null && !text.isBlank());
    }
}
