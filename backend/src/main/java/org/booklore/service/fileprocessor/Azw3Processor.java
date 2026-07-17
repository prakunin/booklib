package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.CoverExtraction;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.CoverProbeOutcome;
import org.booklore.model.enums.CoverSaveOutcome;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.Azw3MetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.BookCoverUtils;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.booklore.util.FileService.truncate;

@Slf4j
@Service
public class Azw3Processor extends AbstractFileProcessor implements BookFileProcessor {

    private final Azw3MetadataExtractor azw3MetadataExtractor;

    public Azw3Processor(BookRepository bookRepository,
                         BookAdditionalFileRepository bookAdditionalFileRepository,
                         BookCreatorService bookCreatorService,
                         BookMapper bookMapper,
                         FileService fileService,
                         MetadataMatchService metadataMatchService,
                         SidecarMetadataWriter sidecarMetadataWriter,
                         Azw3MetadataExtractor azw3MetadataExtractor) {
        super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService, metadataMatchService, sidecarMetadataWriter);
        this.azw3MetadataExtractor = azw3MetadataExtractor;
    }

    @Override
    public BookEntity processNewFile(LibraryFile libraryFile) {
        BookFileType fileType = determineFileType(libraryFile.getFileName());
        BookEntity bookEntity = bookCreatorService.createShellBook(libraryFile, fileType);
        setBookMetadata(bookEntity);
        boolean coverGenerated = generateCover(bookEntity);
        if (!coverGenerated) {
            var folder = getBookFolderForCoverFallback(libraryFile);
            if (folder != null) {
                coverGenerated = generateCoverFromFolderImage(bookEntity, folder);
            }
        }
        if (coverGenerated) {
            FileService.setBookCoverPath(bookEntity.getMetadata());
            bookEntity.setBookCoverHash(BookCoverUtils.generateCoverHash());
        }
        return bookEntity;
    }

    @Override
    public boolean generateCover(BookEntity bookEntity) {
        return generateCover(bookEntity, bookEntity.getPrimaryBookFile());
    }

    /**
     * Reads the cover and writes it, for callers that just want the file on disk and are entitled to
     * overwrite whatever is there. Built on {@link #extractCover} so there is only one way to read a
     * cover out of an AZW3 - the read and the write are separate steps, and this is both in a row.
     */
    @Override
    public boolean generateCover(BookEntity bookEntity, BookFileEntity bookFile) {
        CoverExtraction extraction = extractCover(bookEntity, bookFile);
        if (extraction.outcome() != CoverProbeOutcome.COVER_FOUND) {
            return false;
        }
        return fileService.saveCoverImageFromBytes(bookEntity.getId(), extraction.data()) == CoverSaveOutcome.SAVED;
    }

    /**
     * Pure read: opens the AZW3, pulls the cover bytes out, writes nothing and touches no state.
     * An AZW3 with no cover record is reported as a null rather than by failing, so the clean miss
     * is provable here and {@code NO_COVER_FOUND} is honest.
     */
    @Override
    public CoverExtraction extractCover(BookEntity bookEntity, BookFileEntity bookFile) {
        byte[] coverData;
        try {
            File azw3File = FileUtils.getBookFullPath(bookEntity, bookFile).toFile();
            coverData = azw3MetadataExtractor.extractCover(azw3File);
        } catch (Exception e) {
            // Could not look - not proof there is nothing to find.
            log.error("Error extracting cover from AZW3 '{}': {}", bookFile.getFileName(), e.getMessage(), e);
            return CoverExtraction.readFailed();
        }
        if (coverData == null || coverData.length == 0) {
            log.warn("No cover image found in AZW3 '{}'", bookFile.getFileName());
            return CoverExtraction.noCoverFound();
        }
        return CoverExtraction.found(coverData);
    }

    @Override
    public List<BookFileType> getSupportedTypes() {
        return List.of(BookFileType.AZW3);
    }

    private BookFileType determineFileType(String fileName) {
        return BookFileType.AZW3;
    }

    private void setBookMetadata(BookEntity bookEntity) {
        File bookFile = new File(bookEntity.getFullFilePath().toUri());
        BookMetadata azw3Metadata = azw3MetadataExtractor.extractMetadata(bookFile);
        if (azw3Metadata == null) return;

        BookMetadataEntity metadata = bookEntity.getMetadata();

        metadata.setTitle(truncate(azw3Metadata.getTitle(), 1000));
        metadata.setSubtitle(truncate(azw3Metadata.getSubtitle(), 1000));
        metadata.setDescription(truncate(azw3Metadata.getDescription(), 5000));
        metadata.setPublisher(truncate(azw3Metadata.getPublisher(), 1000));
        metadata.setPublishedDate(azw3Metadata.getPublishedDate());
        metadata.setSeriesName(truncate(azw3Metadata.getSeriesName(), 1000));
        metadata.setSeriesNumber(azw3Metadata.getSeriesNumber());
        metadata.setSeriesTotal(azw3Metadata.getSeriesTotal());
        metadata.setIsbn13(truncate(azw3Metadata.getIsbn13(), 13));
        metadata.setIsbn10(truncate(azw3Metadata.getIsbn10(), 10));
        metadata.setPageCount(azw3Metadata.getPageCount());

        String lang = azw3Metadata.getLanguage();
        metadata.setLanguage(truncate((lang == null || "UND".equalsIgnoreCase(lang)) ? "en" : lang, 10));

        metadata.setAsin(truncate(azw3Metadata.getAsin(), 10));
        metadata.setAmazonRating(azw3Metadata.getAmazonRating());
        metadata.setAmazonReviewCount(azw3Metadata.getAmazonReviewCount());
        metadata.setGoodreadsId(truncate(azw3Metadata.getGoodreadsId(), 100));
        metadata.setGoodreadsRating(azw3Metadata.getGoodreadsRating());
        metadata.setGoodreadsReviewCount(azw3Metadata.getGoodreadsReviewCount());
        metadata.setHardcoverId(truncate(azw3Metadata.getHardcoverId(), 100));
        metadata.setHardcoverRating(azw3Metadata.getHardcoverRating());
        metadata.setHardcoverReviewCount(azw3Metadata.getHardcoverReviewCount());
        metadata.setGoogleId(truncate(azw3Metadata.getGoogleId(), 100));
        metadata.setComicvineId(truncate(azw3Metadata.getComicvineId(), 100));
        metadata.setRanobedbId(truncate(azw3Metadata.getRanobedbId(), 100));
        metadata.setRanobedbRating(azw3Metadata.getRanobedbRating());

        bookCreatorService.addAuthorsToBook(azw3Metadata.getAuthors(), bookEntity);

        if (azw3Metadata.getCategories() != null) {
            Set<String> validSubjects = azw3Metadata.getCategories().stream()
                    .filter(s -> s != null && !s.isBlank() && s.length() <= 100 && !s.contains("\n") && !s.contains("\r") && !s.contains("  "))
                    .collect(Collectors.toSet());
            bookCreatorService.addCategoriesToBook(validSubjects, bookEntity);
        }
    }

}

