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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
@SuppressWarnings("java:S112")
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
            var created = core.getCreated();
            return build(core.getTitle(), core.getCreator(), created == null ? null : created.toInstant());
        }
    }

    private BookMetadata extractOle2(File file) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(file, true)) {
            SummaryInformation summary = (SummaryInformation) PropertySetFactory.create(
                    fs.getRoot(), SummaryInformation.DEFAULT_STREAM_NAME);
            var created = summary.getCreateDateTime();
            return build(summary.getTitle(), summary.getAuthor(), created == null ? null : created.toInstant());
        }
    }

    private BookMetadata build(String title, String author, Instant created) {
        return BookMetadata.builder()
                .title(StringUtils.trimToNull(title))
                .authors(StringUtils.isBlank(author) ? List.of() : List.of(author.strip()))
                .publishedDate(toLocalDate(created))
                .build();
    }

    /**
     * A document's creation date is stored in {@code publishedDate}, which elsewhere means the year an
     * edition was published. Reusing it avoids a migration on {@code book_metadata}, at the cost of
     * documents sorting by year alongside book editions as if the two dates meant the same thing.
     * Deliberate, and the reason a year-based filter can look odd for documents.
     * <p>
     * Converted in UTC rather than the default zone so the same file yields the same date on any host.
     */
    private LocalDate toLocalDate(Instant created) {
        return created == null ? null : created.atZone(ZoneOffset.UTC).toLocalDate();
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
