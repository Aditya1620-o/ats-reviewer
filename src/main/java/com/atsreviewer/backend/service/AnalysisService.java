package com.atsreviewer.backend.service;

import com.atsreviewer.backend.engine.model.ScoreResult;

import java.util.UUID;

public interface AnalysisService {
    boolean markProcessing(UUID analysisId);

    AnalysisInputs loadInputs(UUID analysisId);

    void saveResult(UUID analysisId, ScoreResult result);

    void markFailed(UUID analysisId, String errorCode);
}
