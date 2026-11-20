package com.atsreviewer.backend.engine.model;

import java.util.List;

public record ScoreResult(
        double score,
        List<ScoreItem> items,
        String engineVersion
) {
    public ScoreResult {
        items = List.copyOf(items);
    }
}
