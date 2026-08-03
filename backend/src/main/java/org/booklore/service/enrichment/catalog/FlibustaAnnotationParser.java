package org.booklore.service.enrichment.catalog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads one annotations document — the per-archive XML of the Flibusta catalog:
 *
 * <pre>{@code
 * <folder name="f.fb2-173909-177717.zip">
 *   <file name="110119.fb2">
 *     <p>First paragraph.</p>
 *     <p>Second paragraph.</p>
 *   </file>
 * </folder>
 * }</pre>
 * <p>
 * Streamed rather than DOM-parsed: a single document covers a whole archive, which runs to several
 * megabytes and thousands of books, and the caller only ever keeps the resulting map.
 */
@Slf4j
@Component
public class FlibustaAnnotationParser {

    private static final String PARAGRAPH_SEPARATOR = "\n\n";

    private final XMLInputFactory inputFactory = createInputFactory();

    /**
     * @return entry name (e.g. {@code "110119.fb2"}) to annotation text; empty when the document is
     * unreadable, because a broken annotations file must not fail the enrichment of a book
     */
    public Map<String, String> parse(byte[] xml) {
        if (xml == null || xml.length == 0) {
            return Map.of();
        }
        Map<String, String> annotations = new HashMap<>();
        XMLStreamReader reader = null;
        try {
            reader = inputFactory.createXMLStreamReader(new ByteArrayInputStream(xml), StandardCharsets.UTF_8.name());
            readDocument(reader, annotations);
        } catch (XMLStreamException e) {
            log.warn("Could not parse annotations document ({} bytes): {}", xml.length, e.getMessage());
        } finally {
            closeQuietly(reader);
        }
        return annotations;
    }

    private void readDocument(XMLStreamReader reader, Map<String, String> annotations) throws XMLStreamException {
        String currentEntry = null;
        StringBuilder currentText = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        boolean insideParagraph = false;

        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    if ("file".equals(reader.getLocalName())) {
                        currentEntry = reader.getAttributeValue(null, "name");
                        currentText.setLength(0);
                    } else if ("p".equals(reader.getLocalName())) {
                        insideParagraph = true;
                        paragraph.setLength(0);
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (insideParagraph) {
                        paragraph.append(reader.getText());
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if ("p".equals(reader.getLocalName())) {
                        insideParagraph = false;
                        appendParagraph(currentText, paragraph.toString());
                    } else if ("file".equals(reader.getLocalName())) {
                        storeAnnotation(annotations, currentEntry, currentText.toString());
                        currentEntry = null;
                    }
                }
                default -> {
                    // comments, processing instructions and whitespace carry nothing we need
                }
            }
        }
    }

    private void appendParagraph(StringBuilder target, String paragraph) {
        String trimmed = paragraph.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(PARAGRAPH_SEPARATOR);
        }
        target.append(trimmed);
    }

    private void storeAnnotation(Map<String, String> annotations, String entryName, String text) {
        if (entryName == null || entryName.isBlank() || text.isBlank()) {
            return;
        }
        annotations.put(entryName, text);
    }

    private static XMLInputFactory createInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // The documents are local data files, but they are still untrusted input: disable external
        // entity resolution and DTD support so a doctored catalog cannot reach the filesystem.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    private void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException e) {
            log.debug("Could not close annotations reader: {}", e.getMessage());
        }
    }
}
