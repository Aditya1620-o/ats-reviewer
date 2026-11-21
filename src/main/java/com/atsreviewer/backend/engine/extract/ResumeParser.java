package com.atsreviewer.backend.engine.extract;

import com.atsreviewer.backend.engine.model.ParsedResume;

import java.io.IOException;
import java.util.Locale;

public final class ResumeParser {
    public static final String PDF_MIME_TYPE = "application/pdf";
    public static final String DOCX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final PdfExtractor pdfExtractor;
    private final DocxExtractor docxExtractor;

    public ResumeParser() {
        this(new PdfExtractor(), new DocxExtractor());
    }

    public ResumeParser(PdfExtractor pdfExtractor, DocxExtractor docxExtractor) {
        if (pdfExtractor == null) {
            throw new IllegalArgumentException("pdfExtractor cannot be null");
        }
        if (docxExtractor == null) {
            throw new IllegalArgumentException("docxExtractor cannot be null");
        }
        this.pdfExtractor = pdfExtractor;
        this.docxExtractor = docxExtractor;
    }

    public ParsedResume parse(String mimeType, byte[] fileBytes) throws IOException {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("MIME type is required");
        }
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("Resume file is empty");
        }

        String normalizedMimeType = mimeType.toLowerCase(Locale.ROOT).split(";")[0].trim();

        if (PDF_MIME_TYPE.equals(normalizedMimeType)) {
            return pdfExtractor.extract(fileBytes);
        }
        if (DOCX_MIME_TYPE.equals(normalizedMimeType)) {
            return docxExtractor.extract(fileBytes);
        }

        throw new IllegalArgumentException("Unsupported resume type: " + mimeType);
    }
}
