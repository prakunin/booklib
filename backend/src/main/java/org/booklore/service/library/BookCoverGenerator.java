package org.booklore.service.library;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.service.metadata.extractor.AudiobookMetadataExtractor;
import org.booklore.service.metadata.extractor.CoverExtractionException;
import org.booklore.service.metadata.extractor.FileMetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.util.BookCoverUtils;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Handles cover image generation from book files.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookCoverGenerator {

    private final BookRepository bookRepository;
    private final FileService fileService;
    private final MetadataExtractorFactory metadataExtractorFactory;
    private final AudiobookMetadataExtractor audiobookMetadataExtractor;

    /**
     * Generates a cover image from an additional file and attaches it to the book.
     */
    public void generateCoverFromAdditionalFile(BookEntity bookEntity, LibraryFile additionalFile) {
        BookFileType additionalType = additionalFile.getBookFileType();
        boolean additionalIsAudiobook = additionalType == BookFileType.AUDIOBOOK;

        if (!bookEntity.hasFiles()) {
            try {
                if (additionalIsAudiobook) {
                    generateAudiobookCoverFromFile(bookEntity, additionalFile);
                } else {
                    generateEbookCoverFromFile(bookEntity, additionalFile);
                }
            } catch (Exception e) {
                log.warn("Failed to generate cover from additional file {}: {}", additionalFile.getFileName(), e.getMessage());
            }
            return;
        }

        BookFileType primaryType = bookEntity.getPrimaryBookFile().getBookType();
        boolean primaryIsAudiobook = primaryType == BookFileType.AUDIOBOOK;

        if (primaryIsAudiobook == additionalIsAudiobook) {
            return;
        }

        try {
            if (additionalIsAudiobook) {
                generateAudiobookCoverFromFile(bookEntity, additionalFile);
            } else {
                generateEbookCoverFromFile(bookEntity, additionalFile);
            }
        } catch (Exception e) {
            log.warn("Failed to generate cover from additional file {}: {}", additionalFile.getFileName(), e.getMessage());
        }
    }

    private void generateAudiobookCoverFromFile(BookEntity bookEntity, LibraryFile audioFile) {
        try {
            File file = getFileForCoverExtraction(audioFile);
            if (file == null || !file.exists()) {
                log.debug("Audio file not found for cover extraction: {}", audioFile.getFileName());
                return;
            }

            byte[] coverData = extractAudiobookCoverBytes(file, audioFile.getFileName());
            if (coverData == null) {
                return;
            }

            try (ByteArrayInputStream bais = new ByteArrayInputStream(coverData)) {
                BufferedImage originalImage = FileService.readImage(bais);
                if (originalImage == null) {
                    log.warn("Failed to decode cover image for audiobook '{}'", audioFile.getFileName());
                    return;
                }
                boolean saved = fileService.saveAudiobookCoverImages(originalImage, bookEntity.getId());
                originalImage.flush();

                if (saved) {
                    if (bookEntity.getMetadata() != null) {
                        bookEntity.getMetadata().setAudiobookCoverUpdatedOn(Instant.now());
                    } else {
                        log.debug("Skipping audiobook cover update on metadata for book ID {}: metadata is null", bookEntity.getId());
                    }
                    bookEntity.setAudiobookCoverHash(BookCoverUtils.generateCoverHash());
                    bookRepository.save(bookEntity);
                    log.info("Generated audiobook cover from additional file: {}", audioFile.getFileName());
                }
            }
        } catch (Exception e) {
            log.warn("Error generating audiobook cover from {}: {}", audioFile.getFileName(), e.getMessage());
        }
    }

    /**
     * Extracts audiobook cover bytes, logging and returning {@code null} on either a failed read
     * or a legitimate absence of an embedded cover.
     */
    private byte[] extractAudiobookCoverBytes(File file, String fileName) {
        byte[] coverData;
        try {
            coverData = audiobookMetadataExtractor.extractCover(file);
        } catch (CoverExtractionException e) {
            // Generating a cover for an extra file attached to an existing book is best-effort,
            // so a failed read is still just "no cover this time" here - but it is logged as the
            // different thing it is, rather than as the clean miss below.
            log.warn("Could not read a cover from audiobook '{}': {}", fileName, e.getMessage());
            return null;
        }
        if (coverData == null) {
            log.debug("No cover image found in audiobook '{}'", fileName);
        }
        return coverData;
    }

    private void generateEbookCoverFromFile(BookEntity bookEntity, LibraryFile ebookFile) {
        try {
            File file = ebookFile.getFullPath().toFile();
            if (!file.exists()) {
                log.debug("Ebook file not found for cover extraction: {}", ebookFile.getFileName());
                return;
            }

            var extractor = metadataExtractorFactory.getExtractor(ebookFile.getBookFileType());
            if (extractor == null) {
                log.debug("No extractor available for file type: {}", ebookFile.getBookFileType());
                return;
            }

            byte[] coverData = extractEbookCoverBytes(extractor, file, ebookFile.getFileName());
            if (coverData == null) {
                return;
            }

            try (ByteArrayInputStream bais = new ByteArrayInputStream(coverData)) {
                BufferedImage originalImage = FileService.readImage(bais);
                if (originalImage == null) {
                    log.warn("Failed to decode cover image for ebook '{}'", ebookFile.getFileName());
                    return;
                }
                boolean saved = fileService.saveCoverImages(originalImage, bookEntity.getId());
                originalImage.flush();

                if (saved) {
                    if (bookEntity.getMetadata() != null) {
                        FileService.setBookCoverPath(bookEntity.getMetadata());
                    } else {
                        log.debug("Skipping ebook cover path update for book ID {}: metadata is null", bookEntity.getId());
                    }
                    bookEntity.setBookCoverHash(BookCoverUtils.generateCoverHash());
                    bookRepository.save(bookEntity);
                    log.info("Generated ebook cover from additional file: {}", ebookFile.getFileName());
                }
            }
        } catch (Exception e) {
            log.warn("Error generating ebook cover from {}: {}", ebookFile.getFileName(), e.getMessage());
        }
    }

    /**
     * Extracts ebook cover bytes, logging and returning {@code null} on either a failed read
     * or a legitimate absence of an embedded cover.
     */
    private byte[] extractEbookCoverBytes(FileMetadataExtractor extractor, File file, String fileName) {
        byte[] coverData;
        try {
            coverData = extractor.extractCover(file);
        } catch (CoverExtractionException e) {
            log.warn("Could not read a cover from ebook '{}': {}", fileName, e.getMessage());
            return null;
        }
        if (coverData == null) {
            log.debug("No cover image found in ebook '{}'", fileName);
        }
        return coverData;
    }

    private File getFileForCoverExtraction(LibraryFile libraryFile) {
        if (libraryFile.isFolderBased()) {
            Path folderPath = libraryFile.getFullPath();
            return FileUtils.getFirstAudioFileInFolder(folderPath)
                    .map(Path::toFile)
                    .orElse(null);
        } else {
            return libraryFile.getFullPath().toFile();
        }
    }
}
