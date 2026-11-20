package com.atsreviewer.backend.engine.model;

public record ScoreItem(
        int skillId,
        String name,
        double weight,
        Status status,
        Section foundIn,
        double points
) {}
