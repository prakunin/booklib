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
import org.booklore.service.metadata.extractor.MobiMetadataExtractor;
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
public class MobiProcessor extends AbstractFileProcessor implements BookFileProcessor {

    private final MobiMetadataExtractor mobiMetadataExtractor;

    public MobiProcessor(BookRepository bookRepository,
                         BookAdditionalFileRepository bookAdditionalFileRepository,
                         BookCreatorService bookCreatorService,
                         BookMapper bookMapper,
                         FileService fileService,
                         MetadataMatchService metadataMatchService,
                         SidecarMetadataWriter sidecarMetadataWriter,
                         MobiMetadataExtractor mobiMetadataExtractor) {
        super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService, metadataMatchService, sidecarMetadataWriter);
        this.mobiMetadataExtractor = mobiMetadataExtractor;
    }

    @Override
    public BookEntity processNewFile(LibraryFile libraryFile) {
        BookFileType fileType = determineFileType();
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
     * cover out of a MOBI - the read and the write are separate steps, and this is both in a row.
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
     * Pure read: opens the MOBI, pulls the cover bytes out, writes nothing and touches no state.
     * A MOBI with no cover record is reported as a null rather than by failing, so the clean miss is
     * provable here and {@code NO_COVER_FOUND} is honest.
     */
    @Override
    public CoverExtraction extractCover(BookEntity bookEntity, BookFileEntity bookFile) {
        byte[] coverData;
        try {
            File mobiFile = FileUtils.getBookFullPath(bookEntity, bookFile).toFile();
            coverData = mobiMetadataExtractor.extractCover(mobiFile);
        } catch (Exception e) {
            // Could not look - not proof there is nothing to find.
            log.error("Error extracting cover from MOBI '{}': {}", bookFile.getFileName(), e.getMessage(), e);
            return CoverExtraction.readFailed();
        }
        if (coverData == null || coverData.length == 0) {
            log.warn("No cover image found in MOBI '{}'", bookFile.getFileName());
            return CoverExtraction.noCoverFound();
        }
        return CoverExtraction.found(coverData);
    }

    @Override
    public List<BookFileType> getSupportedTypes() {
        return List.of(BookFileType.MOBI);
    }

    private BookFileType determineFileType() {
        return BookFileType.MOBI;
    }

    private void setBookMetadata(BookEntity bookEntity) {
        File bookFile = new File(bookEntity.getFullFilePath().toUri());
        BookMetadata mobiMetadata = mobiMetadataExtractor.extractMetadata(bookFile);
        if (mobiMetadata == null) return;

        BookMetadataEntity metadata = bookEntity.getMetadata();

        metadata.setTitle(truncate(mobiMetadata.getTitle(), 1000));
        metadata.setSubtitle(truncate(mobiMetadata.getSubtitle(), 1000));
        metadata.setDescription(truncate(mobiMetadata.getDescription(), 2000));
        metadata.setPublisher(truncate(mobiMetadata.getPublisher(), 1000));
        metadata.setPublishedDate(mobiMetadata.getPublishedDate());
        metadata.setSeriesName(truncate(mobiMetadata.getSeriesName(), 1000));
        metadata.setSeriesNumber(mobiMetadata.getSeriesNumber());
        metadata.setSeriesTotal(mobiMetadata.getSeriesTotal());
        metadata.setIsbn13(truncate(mobiMetadata.getIsbn13(), 13));
        metadata.setIsbn10(truncate(mobiMetadata.getIsbn10(), 10));
        metadata.setPageCount(mobiMetadata.getPageCount());

        String lang = mobiMetadata.getLanguage();
        metadata.setLanguage(truncate((lang == null || "UND".equalsIgnoreCase(lang)) ? "en" : lang, 10));

        metadata.setAsin(truncate(mobiMetadata.getAsin(), 10));
        metadata.setAmazonRating(mobiMetadata.getAmazonRating());
        metadata.setAmazonReviewCount(mobiMetadata.getAmazonReviewCount());
        metadata.setGoodreadsId(truncate(mobiMetadata.getGoodreadsId(), 100));
        metadata.setGoodreadsRating(mobiMetadata.getGoodreadsRating());
        metadata.setGoodreadsReviewCount(mobiMetadata.getGoodreadsReviewCount());
        metadata.setHardcoverId(truncate(mobiMetadata.getHardcoverId(), 100));
        metadata.setHardcoverRating(mobiMetadata.getHardcoverRating());
        metadata.setHardcoverReviewCount(mobiMetadata.getHardcoverReviewCount());
        metadata.setGoogleId(truncate(mobiMetadata.getGoogleId(), 100));
        metadata.setComicvineId(truncate(mobiMetadata.getComicvineId(), 100));
        metadata.setRanobedbId(truncate(mobiMetadata.getRanobedbId(), 100));
        metadata.setRanobedbRating(mobiMetadata.getRanobedbRating());

        bookCreatorService.addAuthorsToBook(mobiMetadata.getAuthors(), bookEntity);

        if (mobiMetadata.getCategories() != null) {
            Set<String> validSubjects = mobiMetadata.getCategories().stream()
                    .filter(s -> s != null && !s.isBlank() && s.length() <= 100 && !s.contains("\n") && !s.contains("\r") && !s.contains("  "))
                    .collect(Collectors.toSet());
            bookCreatorService.addCategoriesToBook(validSubjects, bookEntity);
        }
    }

}

