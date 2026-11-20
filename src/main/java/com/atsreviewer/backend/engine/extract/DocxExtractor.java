package com.atsreviewer.backend.engine.extract;

import com.atsreviewer.backend.engine.model.ParsedResume;
import com.atsreviewer.backend.engine.model.Section;
import com.atsreviewer.backend.engine.model.TextBlock;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DocxExtractor {
    public ParsedResume extract(byte[] docxBytes) throws IOException {
        if (docxBytes == null || docxBytes.length == 0) {
            throw new IllegalArgumentException("DOCX bytes cannot be empty");
        }

        List<TextBlock> blocks = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText().trim();
                if (!text.isEmpty()) {
                    blocks.add(new TextBlock(text, 1, 0, 0, 0, 0));
                }
            }
        }

        List<String> warnings = blocks.isEmpty()
                ? List.of("NO_TEXT_EXTRACTED")
                : List.of();

        return new ParsedResume(blocks, groupSections(blocks), warnings);
    }

    private Map<Section, String> groupSections(List<TextBlock> blocks) {
        Map<Section, String> sections = new EnumMap<>(Section.class);
        Section current = Section.OTHER;
        StringBuilder currentText = new StringBuilder();

        for (TextBlock block : blocks) {
            String text = block.text().trim();
            Section heading = findHeading(text);
            if (heading != null) {
                saveSection(sections, current, currentText);
                current = heading;
                currentText = new StringBuilder();
            } else {
                if (!currentText.isEmpty()) {
                    currentText.append('\n');
                }
                currentText.append(text);
            }
        }
        saveSection(sections, current, currentText);

        return sections;
    }

    private Section findHeading(String text) {
        String heading = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim();
        if (heading.equals("experience") || heading.startsWith("work experience")
                || heading.startsWith("professional experience")) return Section.EXPERIENCE;
        if (heading.equals("projects") || heading.startsWith("personal projects")) return Section.PROJECTS;
        if (heading.equals("skills") || heading.startsWith("technical skills")) return Section.SKILLS;
        if (heading.equals("education") || heading.startsWith("academic education")) return Section.EDUCATION;
        return null;
    }

    private void saveSection(Map<Section, String> sections, Section section, StringBuilder text) {
        if (!text.isEmpty()) {
            sections.merge(section, text.toString(), (oldText, newText) -> oldText + "\n" + newText);
        }
    }
}
