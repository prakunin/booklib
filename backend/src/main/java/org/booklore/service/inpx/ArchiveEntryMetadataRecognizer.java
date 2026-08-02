package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.metadata.extractor.DocMetadataExtractor;
import org.booklore.service.metadata.extractor.FileMetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Recognises an INPX archive entry by its real extension, layering the best available source over a
 * filename baseline.
 * <p>
 * The extractor is chosen by the entry's extension, not by the stored {@link BookFileType}: a Word
 * document and HTML publication types may be rendition-backed without a regular extractor.
 * Whatever an available extractor leaves blank is filled from the filename
 * baseline, and Smart Enrichment refines the result later. Formats with no extractor (djvu, rtf, …)
 * are recognised from the filename alone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveEntryMetadataRecognizer {

    private final MetadataExtractorFactory metadataExtractorFactory;
    private final DocMetadataExtractor docMetadataExtractor;
    private final InpxFilenameMetadataParser filenameMetadataParser;

    /**
     * The stored file type: a readable type when the extension has one, else the download-only OTHER.
     * <p>
     * Resolved through {@link BookFileExtension} rather than {@code BookFileType.fromExtension},
     * because Word documents deliberately carry no extensions on the type - they are recognised by
     * extension so that library folders and archive entries reach the same reader.
     */
    public BookFileType resolveBookType(String entryName) {
        String extension = extension(entryName);
        if ("html".equals(extension) || "htm".equals(extension)) {
            return BookFileType.HTML;
        }
        return BookFileExtension.fromFileName(entryName)
                .map(BookFileExtension::getType)
                .orElse(BookFileType.OTHER);
    }

    /** Whether a metadata extractor exists for the entry — the gate for materialising it on refresh. */
    public boolean hasExtractor(String entryName) {
        String extension = extension(entryName);
        return "doc".equals(extension) || "docx".equals(extension)
                || BookFileType.fromExtension(extension).isPresent();
    }

    public boolean isGenericArchive(String entryName) {
        String extension = extension(entryName);
        return "zip".equals(extension) || "rar".equals(extension) || "7z".equals(extension);
    }

    /**
     * Recognises the entry. With a {@code file} the per-format extractor runs and its values win;
     * with {@code null} (or no extractor for the format) only the filename baseline is used.
     */
    public BookMetadata recognize(String entryName, File file) {
        InpxFilenameMetadataParser.ParsedName baseline = filenameMetadataParser.parse(entryName);
        FileMetadataExtractor extractor = file == null ? null : extractorFor(entryName);
        BookMetadata extracted = extractor == null ? null : safeExtract(extractor, file, entryName);
        return merge(extracted, baseline);
    }

    private FileMetadataExtractor extractorFor(String entryName) {
        String extension = extension(entryName);
        if ("doc".equals(extension) || "docx".equals(extension)) {
            return docMetadataExtractor;
        }
        return BookFileType.fromExtension(extension)
                .map(metadataExtractorFactory::getExtractor)
                .orElse(null);
    }

    private BookMetadata safeExtract(FileMetadataExtractor extractor, File file, String entryName) {
        try {
            return extractor.extractMetadata(file);
        } catch (RuntimeException e) {
            log.warn("Metadata extraction failed for archive entry {}: {}", entryName, e.getMessage());
            return null;
        }
    }

    private BookMetadata merge(BookMetadata extracted, InpxFilenameMetadataParser.ParsedName baseline) {
        List<String> baselineAuthors = baseline.author() == null ? List.of() : List.of(baseline.author());
        if (extracted == null) {
            return BookMetadata.builder()
                    .title(StringUtils.trimToNull(baseline.title()))
                    .authors(baselineAuthors)
                    .build();
        }
        if (StringUtils.isBlank(extracted.getTitle())) {
            extracted.setTitle(StringUtils.trimToNull(baseline.title()));
        }
        if (extracted.getAuthors() == null || extracted.getAuthors().isEmpty()) {
            extracted.setAuthors(baselineAuthors);
        }
        return extracted;
    }

    private String extension(String entryName) {
        if (entryName == null) {
            return "";
        }
        int lastDot = entryName.lastIndexOf('.');
        return lastDot >= 0 && lastDot < entryName.length() - 1
                ? entryName.substring(lastDot + 1).toLowerCase(Locale.ROOT)
                : "";
    }
}
