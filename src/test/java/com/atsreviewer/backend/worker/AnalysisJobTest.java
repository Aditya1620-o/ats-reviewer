package com.atsreviewer.backend.worker;

import com.atsreviewer.backend.engine.Scorer;
import com.atsreviewer.backend.engine.SkillMatcher;
import com.atsreviewer.backend.engine.extract.ResumeParser;
import com.atsreviewer.backend.engine.model.JdSkill;
import com.atsreviewer.backend.engine.model.ScoreResult;
import com.atsreviewer.backend.service.AnalysisInputs;
import com.atsreviewer.backend.service.AnalysisService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisJobTest {
    private static final UUID ANALYSIS_ID = UUID.randomUUID();

    @Test
    void scoresResumeAndSavesResult() throws IOException {
        FakeAnalysisService analyses = new FakeAnalysisService(inputs(
                docx("Experience", "Built services with Java"),
                List.of(new JdSkill(1, "Java", true))));
        AnalysisJob job = createJob(analyses);

        job.run(ANALYSIS_ID);

        assertThat(analyses.events).containsExactly("processing", "load", "save");
        assertThat(analyses.savedResult.score()).isEqualTo(100.0);
    }

    @Test
    void marksResumeWithNoTextFailedWithoutRetrying() throws IOException {
        FakeAnalysisService analyses = new FakeAnalysisService(inputs(
                docx(),
                List.of(new JdSkill(1, "Java", true))));

        createJob(analyses).run(ANALYSIS_ID);

        assertThat(analyses.events).containsExactly("processing", "load", "failed:NO_TEXT_EXTRACTED");
        assertThat(analyses.savedResult).isNull();
    }

    @Test
    void marksCorruptResumeFailed() {
        FakeAnalysisService analyses = new FakeAnalysisService(inputs(
                new byte[]{1, 2, 3},
                List.of(new JdSkill(1, "Java", true))));

        createJob(analyses).run(ANALYSIS_ID);

        assertThat(analyses.events).containsExactly("processing", "load", "failed:CORRUPT_FILE");
    }

    @Test
    void propagatesInputLoadingFailuresForQueueRetry() {
        FakeAnalysisService analyses = new FakeAnalysisService(null);
        analyses.loadFailure = new IllegalStateException("temporary storage failure");

        assertThatThrownBy(() -> createJob(analyses).run(ANALYSIS_ID))
                .isSameAs(analyses.loadFailure);

        assertThat(analyses.events).containsExactly("processing", "load");
    }

    @Test
    void propagatesResultSavingFailuresForQueueRetry() throws IOException {
        FakeAnalysisService analyses = new FakeAnalysisService(inputs(
                docx("Experience", "Built services with Java"),
                List.of(new JdSkill(1, "Java", true))));
        analyses.saveFailure = new IllegalStateException("temporary database failure");

        assertThatThrownBy(() -> createJob(analyses).run(ANALYSIS_ID))
                .isSameAs(analyses.saveFailure);

        assertThat(analyses.events).containsExactly("processing", "load", "save");
    }

    @Test
    void skipsAnalysisThatCannotBeMarkedProcessing() {
        FakeAnalysisService analyses = new FakeAnalysisService(null);
        analyses.shouldProcess = false;

        createJob(analyses).run(ANALYSIS_ID);

        assertThat(analyses.events).containsExactly("processing");
    }

    private AnalysisJob createJob(FakeAnalysisService analyses) {
        return new AnalysisJob(
                analyses,
                new ResumeParser(),
                new Scorer(new SkillMatcher(Map.of("java", 1))));
    }

    private static AnalysisInputs inputs(byte[] resume, List<JdSkill> skills) {
        return new AnalysisInputs(ResumeParser.DOCX_MIME_TYPE, resume, skills);
    }

    private static byte[] docx(String... paragraphs) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String text : paragraphs) {
                document.createParagraph().createRun().setText(text);
            }
            document.write(output);
            return output.toByteArray();
        }
    }

    private static final class FakeAnalysisService implements AnalysisService {
        private final AnalysisInputs inputs;
        private final List<String> events = new ArrayList<>();
        private boolean shouldProcess = true;
        private RuntimeException loadFailure;
        private RuntimeException saveFailure;
        private ScoreResult savedResult;
        private String failureCode;

        private FakeAnalysisService(AnalysisInputs inputs) {
            this.inputs = inputs;
        }

        @Override
        public boolean markProcessing(UUID analysisId) {
            events.add("processing");
            return shouldProcess;
        }

        @Override
        public AnalysisInputs loadInputs(UUID analysisId) {
            events.add("load");
            if (loadFailure != null) {
                throw loadFailure;
            }
            return inputs;
        }

        @Override
        public void saveResult(UUID analysisId, ScoreResult result) {
            events.add("save");
            if (saveFailure != null) {
                throw saveFailure;
            }
            savedResult = result;
        }

        @Override
        public void markFailed(UUID analysisId, String errorCode) {
            events.add("failed:" + errorCode);
            failureCode = errorCode;
        }
    }
}
