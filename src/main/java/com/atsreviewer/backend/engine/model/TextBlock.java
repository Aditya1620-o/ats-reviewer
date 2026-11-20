package com.atsreviewer.backend.engine.model;

public record TextBlock(
        String text,
        int page,
        float x0,
        float y0,
        float x1,
        float y1
) {}
