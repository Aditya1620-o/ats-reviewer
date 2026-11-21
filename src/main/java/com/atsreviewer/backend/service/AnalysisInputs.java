package com.atsreviewer.backend.service;

import com.atsreviewer.backend.engine.model.JdSkill;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record AnalysisInputs(
        String mimeType,
        byte[] resumeBytes,
        List<JdSkill> jdSkills
) {
    public AnalysisInputs {
        Objects.requireNonNull(mimeType, "mimeType cannot be null");
        resumeBytes = Arrays.copyOf(
                Objects.requireNonNull(resumeBytes, "resumeBytes cannot be null"),
                resumeBytes.length);
        jdSkills = List.copyOf(Objects.requireNonNull(jdSkills, "jdSkills cannot be null"));
    }

    @Override
    public byte[] resumeBytes() {
        return Arrays.copyOf(resumeBytes, resumeBytes.length);
    }
}
