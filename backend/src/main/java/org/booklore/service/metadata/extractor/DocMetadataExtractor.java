package org.booklore.service.metadata.extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.hpsf.PropertySetFactory;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.booklore.model.dto.BookMetadata;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Reads title and author from Word documents carried by INPX {@code usr} archives.
 * <p>
 * Two container formats, one purpose: {@code .docx} exposes OOXML core properties through
 * {@link XWPFDocument}, while the legacy {@code .doc} (and any OLE2 document) stores the same facts
 * in an HPSF {@link SummaryInformation} stream read straight off the {@link POIFSFileSystem}. Only
 * the identifying fields are taken; blank properties are left null so the caller falls back to the
 * filename baseline. Documents have no cover concept, so {@link #extractCover} always reports none.
 */
@Slf4j
@Component
public class DocMetadataExtractor implements FileMetadataExtractor {

    @Override
    public BookMetadata extractMetadata(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".docx")) {
                return extractDocx(file);
            }
            return extractOle2(file);
        } catch (Exception e) {
            log.warn("Failed to extract metadata from document: {}", file.getName(), e);
            return null;
        }
    }

    private BookMetadata extractDocx(File file) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(file); XWPFDocument doc = new XWPFDocument(pkg)) {
            var core = doc.getProperties().getCoreProperties();
            return build(core.getTitle(), core.getCreator());
        }
    }

    private BookMetadata extractOle2(File file) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(file, true)) {
            SummaryInformation summary = (SummaryInformation) PropertySetFactory.create(
                    fs.getRoot(), SummaryInformation.DEFAULT_STREAM_NAME);
            return build(summary.getTitle(), summary.getAuthor());
        }
    }

    private BookMetadata build(String title, String author) {
        return BookMetadata.builder()
                .title(StringUtils.trimToNull(title))
                .authors(StringUtils.isBlank(author) ? List.of() : List.of(author.strip()))
                .build();
    }

    /**
     * Documents have no cover. Returning {@code null} states the permanent "read, no cover" verdict
     * this layer's contract asks for.
     */
    @Override
    @SuppressWarnings("java:S1168")
    public byte[] extractCover(File file) {
        return null;
    }
}
