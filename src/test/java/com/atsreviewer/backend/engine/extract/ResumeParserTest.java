package com.atsreviewer.backend.engine.extract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeParserTest {
    private final ResumeParser parser = new ResumeParser();

    @Test
    void rejectsUnsupportedMimeType() {
        assertThatThrownBy(() -> parser.parse("text/plain", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported resume type");
    }

    @Test
    void rejectsMissingMimeType() {
        assertThatThrownBy(() -> parser.parse(" ", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MIME type is required");
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> parser.parse(ResumeParser.PDF_MIME_TYPE, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Resume file is empty");
    }
}
