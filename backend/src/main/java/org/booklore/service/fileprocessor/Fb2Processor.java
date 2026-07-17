package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.CoverProbeOutcome;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.BookCoverUtils;
import org.booklore.util.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.booklore.util.FileService.truncate;

@Slf4j
@Service
public class Fb2Processor extends AbstractFileProcessor implements BookFileProcessor {

    private final Fb2MetadataExtractor fb2MetadataExtractor;
    private final ArchivedBookContentService archivedBookContentService;

    public Fb2Processor(BookRepository bookRepository,
                        BookAdditionalFileRepository bookAdditionalFileRepository,
                        BookCreatorService bookCreatorService,
                        BookMapper bookMapper,
                        FileService fileService,
                        MetadataMatchService metadataMatchService,
                        SidecarMetadataWriter sidecarMetadataWriter,
                        Fb2MetadataExtractor fb2MetadataExtractor,
                        ArchivedBookContentService archivedBookContentService) {
        super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService, metadataMatchService, sidecarMetadataWriter);
        this.fb2MetadataExtractor = fb2MetadataExtractor;
        this.archivedBookContentService = archivedBookContentService;
    }

    @Override
    public BookEntity processNewFile(LibraryFile libraryFile) {
        BookEntity bookEntity = bookCreatorService.createShellBook(libraryFile, BookFileType.FB2);
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

    @Override
    public boolean generateCover(BookEntity bookEntity, BookFileEntity bookFile) {
        return probeCover(bookEntity, bookFile) == CoverProbeOutcome.COVER_FOUND;
    }

    @Override
    public CoverProbeOutcome probeCover(BookEntity bookEntity, BookFileEntity bookFile) {
        File fb2File;
        try {
            // Resolving is the actual archive read (opens the ZIP, extracts the entry): a failure
            // here - IO error, corrupt or temporarily unavailable archive - means we could not look,
            // not that there is no cover, so it must not be reported the same way as a clean miss.
            fb2File = archivedBookContentService.resolve(bookFile).toFile();
        } catch (Exception e) {
            log.error("Error resolving archived FB2 content for '{}': {}", bookFile.getFileName(), e.getMessage(), e);
            return CoverProbeOutcome.READ_FAILED;
        }

        byte[] coverData;
        try {
            coverData = fb2MetadataExtractor.extractCover(fb2File);
        } catch (Exception e) {
            // A malformed or unexpectedly-shaped FB2 must not propagate out of here: both callers
            // (the lazy probe and explicit regeneration) would otherwise turn one bad file into a
            // permanent 500, since the old generateCover caught everything and just returned false.
            log.error("Error extracting cover from FB2 '{}': {}", bookFile.getFileName(), e.getMessage(), e);
            return CoverProbeOutcome.READ_FAILED;
        }
        if (coverData == null || coverData.length == 0) {
            log.warn("No cover image found in FB2 '{}'", bookFile.getFileName());
            return CoverProbeOutcome.NO_COVER_FOUND;
        }

        try {
            boolean saved = saveCoverImage(coverData, bookEntity.getId());
            return saved ? CoverProbeOutcome.COVER_FOUND : CoverProbeOutcome.READ_FAILED;
        } catch (Exception e) {
            log.error("Error generating cover for FB2 '{}': {}", bookFile.getFileName(), e.getMessage(), e);
            return CoverProbeOutcome.READ_FAILED;
        }
    }

    @Override
    public List<BookFileType> getSupportedTypes() {
        return List.of(BookFileType.FB2);
    }

    private void setBookMetadata(BookEntity bookEntity) {
        File bookFile = new File(bookEntity.getFullFilePath().toUri());
        BookMetadata fb2Metadata = fb2MetadataExtractor.extractMetadata(bookFile);
        if (fb2Metadata == null) return;

        BookMetadataEntity metadata = bookEntity.getMetadata();

        metadata.setTitle(truncate(fb2Metadata.getTitle(), 1000));
        metadata.setSubtitle(truncate(fb2Metadata.getSubtitle(), 1000));
        metadata.setDescription(truncate(fb2Metadata.getDescription(), 2000));
        metadata.setPublisher(truncate(fb2Metadata.getPublisher(), 1000));
        metadata.setPublishedDate(fb2Metadata.getPublishedDate());
        metadata.setSeriesName(truncate(fb2Metadata.getSeriesName(), 1000));
        metadata.setSeriesNumber(fb2Metadata.getSeriesNumber());
        metadata.setSeriesTotal(fb2Metadata.getSeriesTotal());
        metadata.setIsbn13(truncate(fb2Metadata.getIsbn13(), 13));
        metadata.setIsbn10(truncate(fb2Metadata.getIsbn10(), 10));
        metadata.setPageCount(fb2Metadata.getPageCount());

        String lang = fb2Metadata.getLanguage();
        metadata.setLanguage(truncate((lang == null || "UND".equalsIgnoreCase(lang)) ? "en" : lang, 10));

        metadata.setAsin(truncate(fb2Metadata.getAsin(), 10));
        metadata.setAmazonRating(fb2Metadata.getAmazonRating());
        metadata.setAmazonReviewCount(fb2Metadata.getAmazonReviewCount());
        metadata.setGoodreadsId(truncate(fb2Metadata.getGoodreadsId(), 100));
        metadata.setGoodreadsRating(fb2Metadata.getGoodreadsRating());
        metadata.setGoodreadsReviewCount(fb2Metadata.getGoodreadsReviewCount());
        metadata.setHardcoverId(truncate(fb2Metadata.getHardcoverId(), 100));
        metadata.setHardcoverRating(fb2Metadata.getHardcoverRating());
        metadata.setHardcoverReviewCount(fb2Metadata.getHardcoverReviewCount());
        metadata.setGoogleId(truncate(fb2Metadata.getGoogleId(), 100));
        metadata.setComicvineId(truncate(fb2Metadata.getComicvineId(), 100));
        metadata.setRanobedbId(truncate(fb2Metadata.getRanobedbId(), 100));
        metadata.setRanobedbRating(fb2Metadata.getRanobedbRating());

        bookCreatorService.addAuthorsToBook(fb2Metadata.getAuthors(), bookEntity);

        if (fb2Metadata.getCategories() != null) {
            Set<String> validSubjects = fb2Metadata.getCategories().stream()
                    .filter(s -> s != null && !s.isBlank() && s.length() <= 100 && !s.contains("\n") && !s.contains("\r") && !s.contains("  "))
                    .collect(Collectors.toSet());
            bookCreatorService.addCategoriesToBook(validSubjects, bookEntity);
        }
    }

    private boolean saveCoverImage(byte[] coverData, long bookId) throws Exception {
        BufferedImage originalImage = FileService.readImage(coverData);
        if (originalImage == null) {
            log.warn("Failed to decode cover image for FB2");
            return false;
        }

        return fileService.saveCoverImages(originalImage, bookId);
    }
}
