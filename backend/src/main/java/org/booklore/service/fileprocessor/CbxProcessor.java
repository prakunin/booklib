package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.CoverExtraction;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.ComicMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.ComicMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.CoverProbeOutcome;
import org.booklore.model.enums.CoverSaveOutcome;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.CbxMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.ArchiveUtils;
import org.booklore.util.BookCoverUtils;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.booklore.util.FileService.truncate;


@Slf4j
@Service
public class CbxProcessor extends AbstractFileProcessor implements BookFileProcessor {

    private static final Pattern UNDERSCORE_HYPHEN_PATTERN = Pattern.compile("[_\\-]");
    private final CbxMetadataExtractor cbxMetadataExtractor;

    public CbxProcessor(BookRepository bookRepository,
                        BookAdditionalFileRepository bookAdditionalFileRepository,
                        BookCreatorService bookCreatorService,
                        BookMapper bookMapper,
                        FileService fileService,
                        MetadataMatchService metadataMatchService,
                        SidecarMetadataWriter sidecarMetadataWriter,
                        CbxMetadataExtractor cbxMetadataExtractor) {
        super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService, metadataMatchService, sidecarMetadataWriter);
        this.cbxMetadataExtractor = cbxMetadataExtractor;
    }

    @Override
    public BookEntity processNewFile(LibraryFile libraryFile) {
        BookEntity bookEntity = bookCreatorService.createShellBook(libraryFile, BookFileType.CBX);
        bookEntity.getPrimaryBookFile().setArchiveType(ArchiveUtils.detectArchiveType(FileUtils.getBookFullPath(bookEntity).toFile()));
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
        extractAndSetMetadata(bookEntity);
        return bookEntity;
    }

    @Override
    public boolean generateCover(BookEntity bookEntity) {
        return generateCover(bookEntity, bookEntity.getPrimaryBookFile());
    }

    /**
     * Reads the cover and writes it, for callers that just want the file on disk and are entitled to
     * overwrite whatever is there. Built on {@link #extractCover} so there is only one way to read a
     * cover out of a comic archive - the read and the write are separate steps, and this is both in
     * a row.
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
     * Pure read: opens the comic archive, pulls the first page's bytes out, writes nothing and
     * touches no state.
     * <p>
     * The clean miss is provable: the extractor returns a null for an archive it read but that holds
     * no image entry. This used to be indistinguishable here - the whole read sat under one
     * {@code catch} that turned an unopenable or corrupt archive into the same "could not find cover
     * image" as an archive with no pages in it.
     */
    @Override
    public CoverExtraction extractCover(BookEntity bookEntity, BookFileEntity bookFile) {
        byte[] coverBytes;
        try {
            Path bookPath = FileUtils.getBookFullPath(bookEntity, bookFile);
            coverBytes = cbxMetadataExtractor.extractCover(bookPath);
        } catch (Exception e) {
            // Could not open or walk the archive - not proof it holds no pages.
            log.error("Error extracting cover from '{}': {}", bookFile.getFileName(), e.getMessage(), e);
            return CoverExtraction.readFailed();
        }
        if (coverBytes == null || coverBytes.length == 0) {
            log.warn("Could not find cover image in '{}' archive", bookFile.getFileName());
            return CoverExtraction.noCoverFound();
        }
        return CoverExtraction.found(coverBytes);
    }

    @Override
    public List<BookFileType> getSupportedTypes() {
        return List.of(BookFileType.CBX);
    }

    private void extractAndSetMetadata(BookEntity bookEntity) {
        try {
            BookMetadata extracted = cbxMetadataExtractor.extractMetadata(FileUtils.getBookFullPath(bookEntity).toFile());
            if (extracted == null) {
                // Fallback to filename-derived title
                setMetadata(bookEntity);
                return;
            }

            BookMetadataEntity metadata = bookEntity.getMetadata();

            // Basic fields
            metadata.setTitle(truncate(extracted.getTitle(), 1000));
            metadata.setSubtitle(truncate(extracted.getSubtitle(), 1000));
            metadata.setDescription(truncate(extracted.getDescription(), 5000));
            metadata.setPublisher(truncate(extracted.getPublisher(), 1000));
            metadata.setPublishedDate(extracted.getPublishedDate());
            metadata.setSeriesName(truncate(extracted.getSeriesName(), 1000));
            metadata.setSeriesNumber(extracted.getSeriesNumber());
            metadata.setSeriesTotal(extracted.getSeriesTotal());
            metadata.setPageCount(extracted.getPageCount());
            metadata.setLanguage(truncate(extracted.getLanguage(), 10));

            // ISBN fields
            metadata.setIsbn13(truncate(extracted.getIsbn13(), 13));
            metadata.setIsbn10(truncate(extracted.getIsbn10(), 10));

            // External IDs
            metadata.setAsin(truncate(extracted.getAsin(), 10));
            metadata.setGoodreadsId(truncate(extracted.getGoodreadsId(), 100));
            metadata.setHardcoverId(truncate(extracted.getHardcoverId(), 100));
            metadata.setHardcoverBookId(truncate(extracted.getHardcoverBookId(), 100));
            metadata.setGoogleId(truncate(extracted.getGoogleId(), 100));
            metadata.setComicvineId(truncate(extracted.getComicvineId(), 100));
            metadata.setLubimyczytacId(truncate(extracted.getLubimyczytacId(), 100));
            metadata.setRanobedbId(truncate(extracted.getRanobedbId(), 100));

            // Ratings
            metadata.setAmazonRating(extracted.getAmazonRating());
            metadata.setAmazonReviewCount(extracted.getAmazonReviewCount());
            metadata.setGoodreadsRating(extracted.getGoodreadsRating());
            metadata.setGoodreadsReviewCount(extracted.getGoodreadsReviewCount());
            metadata.setHardcoverRating(extracted.getHardcoverRating());
            metadata.setHardcoverReviewCount(extracted.getHardcoverReviewCount());
            metadata.setLubimyczytacRating(extracted.getLubimyczytacRating());
            metadata.setRanobedbRating(extracted.getRanobedbRating());

            // Authors
            if (extracted.getAuthors() != null) {
                bookCreatorService.addAuthorsToBook(extracted.getAuthors(), bookEntity);
            }

            // Categories
            if (extracted.getCategories() != null) {
                Set<String> validCategories = extracted.getCategories().stream()
                        .filter(s -> s != null && !s.isBlank() && s.length() <= 100 && !s.contains("\n") && !s.contains("\r") && !s.contains("  "))
                        .collect(Collectors.toSet());
                bookCreatorService.addCategoriesToBook(validCategories, bookEntity);
            }

            // Moods
            if (extracted.getMoods() != null && !extracted.getMoods().isEmpty()) {
                Set<String> validMoods = extracted.getMoods().stream()
                        .filter(s -> s != null && !s.isBlank() && s.length() <= 255)
                        .collect(Collectors.toSet());
                bookCreatorService.addMoodsToBook(validMoods, bookEntity);
            }

            // Tags
            if (extracted.getTags() != null && !extracted.getTags().isEmpty()) {
                Set<String> validTags = extracted.getTags().stream()
                        .filter(s -> s != null && !s.isBlank() && s.length() <= 255)
                        .collect(Collectors.toSet());
                bookCreatorService.addTagsToBook(validTags, bookEntity);
            }
            if (extracted.getComicMetadata() != null) {
                saveComicMetadata(bookEntity, extracted.getComicMetadata());
            }
        } catch (Exception e) {
            log.warn("Failed to extract ComicInfo metadata for '{}': {}", bookEntity.getPrimaryBookFile().getFileName(), e.getMessage());
            // Fallback to filename-derived title
            setMetadata(bookEntity);
        }
    }

    private void setMetadata(BookEntity bookEntity) {
        String baseName = new File(bookEntity.getPrimaryBookFile().getFileName()).getName();
        String extension = FileUtils.getExtension(baseName);
        if (BookFileType.CBX.supports(extension)) {
            baseName = baseName.substring(0, baseName.length() - extension.length() - 1);
        }
        String title = UNDERSCORE_HYPHEN_PATTERN.matcher(baseName).replaceAll(" ").trim();
        bookEntity.getMetadata().setTitle(truncate(title, 1000));
    }

    private void saveComicMetadata(BookEntity bookEntity, ComicMetadata comicDto) {
        Long bookId = bookEntity.getId();
        if (bookId == null) {
            log.warn("Cannot save comic metadata - book ID is null for '{}'",
                    bookEntity.getPrimaryBookFile().getFileName());
            return;
        }

        ComicMetadataEntity comic = new ComicMetadataEntity();
        comic.setBookId(bookId);
        comic.setBookMetadata(bookEntity.getMetadata());
        comic.setIssueNumber(comicDto.getIssueNumber());
        comic.setVolumeName(comicDto.getVolumeName());
        comic.setVolumeNumber(comicDto.getVolumeNumber());
        comic.setStoryArc(comicDto.getStoryArc());
        comic.setStoryArcNumber(comicDto.getStoryArcNumber());
        comic.setAlternateSeries(comicDto.getAlternateSeries());
        comic.setAlternateIssue(comicDto.getAlternateIssue());
        comic.setImprint(comicDto.getImprint());
        comic.setFormat(comicDto.getFormat());
        comic.setBlackAndWhite(comicDto.getBlackAndWhite() != null ? comicDto.getBlackAndWhite() : Boolean.FALSE);
        comic.setManga(comicDto.getManga() != null ? comicDto.getManga() : Boolean.FALSE);
        comic.setReadingDirection(comicDto.getReadingDirection() != null ? comicDto.getReadingDirection() : "ltr");
        comic.setWebLink(comicDto.getWebLink());
        comic.setNotes(comicDto.getNotes());

        // Set on parent - relationships will be populated in saveConnections()
        bookEntity.getMetadata().setComicMetadata(comic);

        // Store the DTO for later processing in saveConnections
        bookCreatorService.setComicMetadataDto(bookEntity, comicDto);
    }
}

