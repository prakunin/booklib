package org.booklore.service.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.booklore.model.document.DocumentBlock;
import org.booklore.model.document.DocumentContent;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a Word document into the single {@link DocumentContent} model every consumer reads.
 * <p>
 * Two container formats, one traversal shape: {@code .docx} exposes paragraphs and their style ids
 * through {@link XWPFDocument}, while the legacy OLE2 {@code .doc} exposes the same through an
 * {@link HWPFDocument} range whose style names come off the stylesheet. Blank paragraphs are
 * dropped, and because a block's ordinal is its <em>source</em> paragraph index, dropping one leaves
 * a gap rather than shifting everything after it.
 */
@Slf4j
@Component
public class DocumentContentExtractor {

    /** Word's built-in outline styles only go to 9; the reader's table of contents uses the first three. */
    private static final int MAX_HEADING_LEVEL = 3;

    public boolean supports(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".doc") || lower.endsWith(".docx");
    }

    public DocumentContent extract(File file) throws IOException {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return lower.endsWith(".docx") ? extractDocx(file) : extractOle2(file);
    }

    private DocumentContent extractDocx(File file) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(java.nio.file.Files.newInputStream(file.toPath()))) {
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            List<DocumentBlock> blocks = new ArrayList<>(paragraphs.size());
            for (int i = 0; i < paragraphs.size(); i++) {
                XWPFParagraph paragraph = paragraphs.get(i);
                addBlock(blocks, i, paragraph.getStyleID(), paragraph.getText());
            }
            return new DocumentContent(blocks);
        }
    }

    private DocumentContent extractOle2(File file) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(file, true);
             HWPFDocument doc = new HWPFDocument(fs)) {
            Range range = doc.getRange();
            List<DocumentBlock> blocks = new ArrayList<>(range.numParagraphs());
            for (int i = 0; i < range.numParagraphs(); i++) {
                Paragraph paragraph = range.getParagraph(i);
                addBlock(blocks, i, styleNameOf(doc, paragraph), paragraph.text());
            }
            return new DocumentContent(blocks);
        }
    }

    private String styleNameOf(HWPFDocument doc, Paragraph paragraph) {
        try {
            var description = doc.getStyleSheet().getStyleDescription(paragraph.getStyleIndex());
            return description == null ? null : description.getName();
        } catch (RuntimeException e) {
            // A malformed stylesheet costs the heading hierarchy, not the text.
            return null;
        }
    }

    private void addBlock(List<DocumentBlock> blocks, int sourceIndex, String styleName, String rawText) {
        String text = StringUtils.trimToNull(rawText == null ? null : rawText.replace('\r', ' '));
        if (text == null) {
            return;
        }
        blocks.add(new DocumentBlock(sourceIndex, headingLevel(styleName), text));
    }

    /**
     * Word writes the built-in outline styles as {@code Heading1} in OOXML style ids and
     * {@code heading 1} in legacy stylesheet names, so both collapse to the same comparison.
     */
    private int headingLevel(String styleName) {
        if (styleName == null) {
            return 0;
        }
        String normalized = styleName.toLowerCase(Locale.ROOT).replace(" ", "");
        if (!normalized.startsWith("heading")) {
            return 0;
        }
        String suffix = normalized.substring("heading".length());
        if (suffix.length() != 1 || !Character.isDigit(suffix.charAt(0))) {
            return 0;
        }
        int level = suffix.charAt(0) - '0';
        return level >= 1 && level <= MAX_HEADING_LEVEL ? level : 0;
    }
}
