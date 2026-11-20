package com.atsreviewer.backend.engine.extract;

import com.atsreviewer.backend.engine.model.ParsedResume;
import com.atsreviewer.backend.engine.model.Section;
import com.atsreviewer.backend.engine.model.TextBlock;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Comparator;

public final class PdfExtractor {
    public ParsedResume extract(byte[] pdfBytes) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF bytes cannot be empty");
        }

        List<TextBlock> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                PositionedTextStripper stripper = new PositionedTextStripper();
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                stripper.getText(document);

                if (stripper.positions.isEmpty()) {
                    warnings.add("IMAGE_ONLY_PAGE:" + pageNumber);
                    continue;
                }

                blocks.addAll(stripPositions(stripper.positions, pageNumber));
            }
        }

        return new ParsedResume(blocks, groupSections(blocks), List.copyOf(warnings));
    }

    private List<TextBlock> stripPositions(List<TextPosition> positions, int pageNumber) {
        Map<Float, List<TextPosition>> lines = new TreeMap<>((first, second) -> Float.compare(second, first));
        for (TextPosition position : positions) {
            float lineKey = Math.round(position.getYDirAdj() * 2) / 2.0f;
            lines.computeIfAbsent(lineKey, key -> new ArrayList<>()).add(position);
        }

        List<TextBlock> blocks = new ArrayList<>();
        for (List<TextPosition> line : lines.values()) {
            line.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));

            StringBuilder text = new StringBuilder();
            float x0 = Float.MAX_VALUE;
            float y0 = Float.MAX_VALUE;
            float x1 = Float.MIN_VALUE;
            float y1 = Float.MIN_VALUE;

            for (TextPosition position : line) {
                String character = position.getUnicode();
                if (character != null) {
                    text.append(character);
                }
                float left = position.getXDirAdj();
                float bottom = position.getYDirAdj();
                float right = left + position.getWidthDirAdj();
                float top = bottom + position.getHeightDir();

                x0 = Math.min(x0, left);
                y0 = Math.min(y0, bottom);
                x1 = Math.max(x1, right);
                y1 = Math.max(y1, top);
            }

            String lineText = text.toString().trim();
            if (!lineText.isEmpty()) {
                blocks.add(new TextBlock(lineText, pageNumber, x0, y0, x1, y1));
            }
        }
        return blocks;
    }

    private static final class PositionedTextStripper extends PDFTextStripper {
        private final List<TextPosition> positions = new ArrayList<>();

        PositionedTextStripper() throws IOException {
            super();
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            positions.add(text);
        }
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
