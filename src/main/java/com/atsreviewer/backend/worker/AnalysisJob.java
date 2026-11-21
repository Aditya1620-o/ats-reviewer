package com.atsreviewer.backend.worker;

import com.atsreviewer.backend.engine.Scorer;
import com.atsreviewer.backend.engine.extract.ResumeParser;
import com.atsreviewer.backend.engine.model.ParsedResume;
import com.atsreviewer.backend.engine.model.ScoreResult;
import com.atsreviewer.backend.service.AnalysisInputs;
import com.atsreviewer.backend.service.AnalysisService;
import org.apache.poi.ooxml.POIXMLException;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

public final class AnalysisJob {
    private static final String NO_TEXT_EXTRACTED = "NO_TEXT_EXTRACTED";
    private static final String CORRUPT_FILE = "CORRUPT_FILE";

    private final AnalysisService analyses;
    private final ResumeParser parser;
    private final Scorer scorer;

    public AnalysisJob(AnalysisService analyses, ResumeParser parser, Scorer scorer) {
        this.analyses = Objects.requireNonNull(analyses, "analyses cannot be null");
        this.parser = Objects.requireNonNull(parser, "parser cannot be null");
        this.scorer = Objects.requireNonNull(scorer, "scorer cannot be null");
    }

    public void run(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId cannot be null");
        if (!analyses.markProcessing(analysisId)) {
            return;
        }

        AnalysisInputs inputs = analyses.loadInputs(analysisId);
        ParsedResume parsed;
        try {
            parsed = parser.parse(inputs.mimeType(), inputs.resumeBytes());
        } catch (IOException | IllegalArgumentException | POIXMLException exception) {
            analyses.markFailed(analysisId, CORRUPT_FILE);
            return;
        }

        if (!parsed.hasText()) {
            analyses.markFailed(analysisId, NO_TEXT_EXTRACTED);
            return;
        }

        ScoreResult result = scorer.score(parsed, inputs.jdSkills());
        analyses.saveResult(analysisId, result);
    }
}
