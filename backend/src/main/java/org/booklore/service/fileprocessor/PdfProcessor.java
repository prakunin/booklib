package org.booklore.service.fileprocessor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.PdfPage;
import org.booklore.mapper.BookMapper;
import org.booklore.model.CoverExtraction;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.CoverProbeOutcome;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.PdfMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.BookCoverUtils;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

import static org.booklore.util.FileService.truncate;

@Slf4j
@Service
public class PdfProcessor extends AbstractFileProcessor implements BookFileProcessor {

    private static final int RENDER_DPI = 150;
    /** Lossless, so encoding a rendered page for {@link #extractCover} costs no image quality. */
    private static final String RENDER_FORMAT = "png";

    private final PdfMetadataExtractor pdfMetadataExtractor;

    public PdfProcessor(BookRepository bookRepository,
                        BookAdditionalFileRepository bookAdditionalFileRepository,
                        BookCreatorService bookCreatorService,
                        BookMapper bookMapper,
                        FileService fileService,
                        MetadataMatchService metadataMatchService,
                        SidecarMetadataWriter sidecarMetadataWriter,
                        PdfMetadataExtractor pdfMetadataExtractor) {
        super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService, metadataMatchService, sidecarMetadataWriter);
        this.pdfMetadataExtractor = pdfMetadataExtractor;
    }

    @Override
    public BookEntity processNewFile(LibraryFile libraryFile) {
        BookEntity bookEntity = bookCreatorService.createShellBook(libraryFile, BookFileType.PDF);
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
     * Renders the cover and writes it straight to disk.
     * <p>
     * Unlike the other processors this is <em>not</em> {@link #extractCover} followed by a save, and
     * deliberately so. A PDF cover is rendered, not extracted: there are no cover bytes in the file
     * to hand around, so {@code extractCover} has to encode the rendered page into an image format
     * purely to satisfy its byte-returning contract - which this method would then have to decode
     * straight back. That round trip buys nothing here (this caller is entitled to overwrite, so it
     * has no claim to win first) and costs a second full-size raster plus the encode on a path that
     * already catches {@link OutOfMemoryError} because rendering large pages is where this
     * processor runs out of heap. Both methods share {@link #renderFirstPage}, so there is still one
     * way to read a PDF cover; they differ only in what they do with the result.
     */
    @Override
    public boolean generateCover(BookEntity bookEntity, BookFileEntity bookFile) {
        File pdfFile = FileUtils.getBookFullPath(bookEntity, bookFile).toFile();
        BufferedImage coverImage = null;
        try (PdfDocument doc = PdfDocument.open(pdfFile.toPath())) {
            coverImage = renderFirstPage(doc);
            return fileService.saveCoverImages(coverImage, bookEntity.getId());
        } catch (OutOfMemoryError _) {
            // Note: Catching OOM is generally discouraged, but for batch processing
            // of potentially large/corrupted PDFs, we prefer graceful degradation
            // over crashing the entire service.
            log.error("Out of memory (heap space exhausted) while generating cover for '{}'. Skipping cover generation.", bookFile.getFileName());
            return false;
        } catch (NegativeArraySizeException _) {
            // This can appear on corrupted PDF, or PDF with such large images that the
            // initial memory buffer is already bigger than the entire JVM heap, therefore
            // it leads to NegativeArrayException (basically run out of memory, and overflows)
            log.warn("Corrupted PDF structure for '{}'. Skipping cover generation.", bookFile.getFileName());
            return false;
        } catch (Exception e) {
            log.warn("Failed to generate cover for '{}': {}", bookFile.getFileName(), e.getMessage());
            return false;
        } finally {
            if (coverImage != null) {
                coverImage.flush(); // Release native resources
            }
        }
    }

    /**
     * Pure read: renders the first page and encodes it, writing nothing and touching no state.
     * <p>
     * This processor can never honestly report {@link CoverProbeOutcome#NO_COVER_FOUND}. A PDF has
     * no embedded cover to be missing - the cover <em>is</em> a render of page one - so any PDF that
     * opens has one, and a PDF that does not open (or has no page one) is a failure to read rather
     * than proof of absence. Its outcomes are therefore only {@code COVER_FOUND} or
     * {@code READ_FAILED}, and a user who regenerates a broken PDF's cover correctly gets the hedged
     * "may have none, or may be unreadable" message rather than a definitive verdict this processor
     * is in no position to give.
     */
    @Override
    public CoverExtraction extractCover(BookEntity bookEntity, BookFileEntity bookFile) {
        File pdfFile = FileUtils.getBookFullPath(bookEntity, bookFile).toFile();
        BufferedImage coverImage = null;
        try (PdfDocument doc = PdfDocument.open(pdfFile.toPath())) {
            coverImage = renderFirstPage(doc);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(coverImage, RENDER_FORMAT, encoded) || encoded.size() == 0) {
                log.warn("Rendered page of '{}' could not be encoded as {}", bookFile.getFileName(), RENDER_FORMAT);
                return CoverExtraction.readFailed();
            }
            return CoverExtraction.found(encoded.toByteArray());
        } catch (OutOfMemoryError _) {
            log.error("Out of memory (heap space exhausted) while extracting cover for '{}'.", bookFile.getFileName());
            return CoverExtraction.readFailed();
        } catch (NegativeArraySizeException _) {
            log.warn("Corrupted PDF structure for '{}'.", bookFile.getFileName());
            return CoverExtraction.readFailed();
        } catch (Exception e) {
            log.warn("Failed to extract cover from '{}': {}", bookFile.getFileName(), e.getMessage());
            return CoverExtraction.readFailed();
        } finally {
            if (coverImage != null) {
                coverImage.flush(); // Release native resources
            }
        }
    }

    @Override
    public List<BookFileType> getSupportedTypes() {
        return List.of(BookFileType.PDF);
    }

    private void extractAndSetMetadata(BookEntity bookEntity) {
        try {
            BookMetadata extracted = pdfMetadataExtractor.extractMetadata(FileUtils.getBookFullPath(bookEntity).toFile());

            applyBasicMetadata(extracted, bookEntity);
            applyExternalIds(extracted, bookEntity);
            applyClassificationsAndRatings(extracted, bookEntity);

        } catch (Exception e) {
            log.warn("Failed to extract PDF metadata for '{}': {}", bookEntity.getPrimaryBookFile().getFileName(), e.getMessage());
        }
    }

    private void applyBasicMetadata(BookMetadata extracted, BookEntity bookEntity) {
        if (StringUtils.isNotBlank(extracted.getTitle())) {
            bookEntity.getMetadata().setTitle(truncate(extracted.getTitle(), 1000));
        }
        if (StringUtils.isNotBlank(extracted.getSubtitle())) {
            bookEntity.getMetadata().setSubtitle(truncate(extracted.getSubtitle(), 1000));
        }
        if (StringUtils.isNotBlank(extracted.getSeriesName())) {
            bookEntity.getMetadata().setSeriesName(truncate(extracted.getSeriesName(), 1000));
        }
        if (extracted.getSeriesNumber() != null) {
            bookEntity.getMetadata().setSeriesNumber(extracted.getSeriesNumber());
        }
        if (extracted.getSeriesTotal() != null) {
            bookEntity.getMetadata().setSeriesTotal(extracted.getSeriesTotal());
        }
        if (extracted.getAuthors() != null) {
            bookCreatorService.addAuthorsToBook(extracted.getAuthors(), bookEntity);
        }
        if (StringUtils.isNotBlank(extracted.getPublisher())) {
            bookEntity.getMetadata().setPublisher(extracted.getPublisher());
        }
        if (StringUtils.isNotBlank(extracted.getDescription())) {
            bookEntity.getMetadata().setDescription(truncate(extracted.getDescription(), 5000));
        }
        if (extracted.getPublishedDate() != null) {
            bookEntity.getMetadata().setPublishedDate(extracted.getPublishedDate());
        }
        if (StringUtils.isNotBlank(extracted.getLanguage())) {
            bookEntity.getMetadata().setLanguage(truncate(extracted.getLanguage(), 10));
        }
        if (extracted.getPageCount() != null) {
            bookEntity.getMetadata().setPageCount(extracted.getPageCount());
        }
    }

    private void applyExternalIds(BookMetadata extracted, BookEntity bookEntity) {
        if (StringUtils.isNotBlank(extracted.getAsin())) {
            bookEntity.getMetadata().setAsin(truncate(extracted.getAsin(), 10));
        }
        if (StringUtils.isNotBlank(extracted.getGoogleId())) {
            bookEntity.getMetadata().setGoogleId(extracted.getGoogleId());
        }
        if (StringUtils.isNotBlank(extracted.getHardcoverId())) {
            bookEntity.getMetadata().setHardcoverId(extracted.getHardcoverId());
        }
        if (StringUtils.isNotBlank(extracted.getHardcoverBookId())) {
            bookEntity.getMetadata().setHardcoverBookId(extracted.getHardcoverBookId());
        }
        if (StringUtils.isNotBlank(extracted.getGoodreadsId())) {
            bookEntity.getMetadata().setGoodreadsId(extracted.getGoodreadsId());
        }
        if (StringUtils.isNotBlank(extracted.getComicvineId())) {
            bookEntity.getMetadata().setComicvineId(extracted.getComicvineId());
        }
        if (StringUtils.isNotBlank(extracted.getRanobedbId())) {
            bookEntity.getMetadata().setRanobedbId(extracted.getRanobedbId());
        }
        if (StringUtils.isNotBlank(extracted.getLubimyczytacId())) {
            bookEntity.getMetadata().setLubimyczytacId(extracted.getLubimyczytacId());
        }
        if (StringUtils.isNotBlank(extracted.getIsbn10())) {
            bookEntity.getMetadata().setIsbn10(truncate(extracted.getIsbn10(), 10));
        }
        if (StringUtils.isNotBlank(extracted.getIsbn13())) {
            bookEntity.getMetadata().setIsbn13(truncate(extracted.getIsbn13(), 13));
        }
    }

    private void applyClassificationsAndRatings(BookMetadata extracted, BookEntity bookEntity) {
        // Categories, moods, and tags
        if (extracted.getCategories() != null && !extracted.getCategories().isEmpty()) {
            bookCreatorService.addCategoriesToBook(extracted.getCategories(), bookEntity);
        }
        if (extracted.getMoods() != null && !extracted.getMoods().isEmpty()) {
            bookCreatorService.addMoodsToBook(extracted.getMoods(), bookEntity);
        }
        if (extracted.getTags() != null && !extracted.getTags().isEmpty()) {
            bookCreatorService.addTagsToBook(extracted.getTags(), bookEntity);
        }

        // Ratings
        if (extracted.getAmazonRating() != null) {
            bookEntity.getMetadata().setAmazonRating(extracted.getAmazonRating());
        }
        if (extracted.getGoodreadsRating() != null) {
            bookEntity.getMetadata().setGoodreadsRating(extracted.getGoodreadsRating());
        }
        if (extracted.getHardcoverRating() != null) {
            bookEntity.getMetadata().setHardcoverRating(extracted.getHardcoverRating());
        }
        if (extracted.getLubimyczytacRating() != null) {
            bookEntity.getMetadata().setLubimyczytacRating(extracted.getLubimyczytacRating());
        }
        if (extracted.getRanobedbRating() != null) {
            bookEntity.getMetadata().setRanobedbRating(extracted.getRanobedbRating());
        }
        if (extracted.getRating() != null) {
            bookEntity.getMetadata().setRating(extracted.getRating());
        }
    }

    /**
     * The one place a PDF's cover is read. Both {@link #generateCover} and {@link #extractCover}
     * come through here so they cannot drift apart on what "the cover of a PDF" means.
     * <p>
     * The render is bounded to the size a cover is actually stored at rather than taken at the full
     * {@link #RENDER_DPI}. {@code FileService} scales every cover down into
     * {@link FileService#MAX_ORIGINAL_WIDTH} x {@link FileService#MAX_ORIGINAL_HEIGHT} regardless,
     * so rendering an A0 page to 4965x7020 in order to shrink it to ~1000px wide was work done to be
     * thrown away - and worse, {@link #extractCover} now PNG-encodes this raster and
     * {@code saveCoverImageFromBytes} decodes it back through {@code readImage}, which rejects
     * anything over {@code MAX_IMAGE_PIXELS} as a decompression bomb. At 150 DPI that cap is 34.8M
     * pixels for an A0 page: the render succeeded, the decode refused it, and the user was told
     * their perfectly good PDF had "a cover image in a format that cannot be read".
     * <p>
     * Bounding here rather than raising the cap is the right side to fix. The cap defends
     * {@code readImage} against <em>untrusted</em> bytes, and it should keep doing so; these bytes
     * are a raster we just produced ourselves, and the honest way to say "this is not a bomb" is not
     * to let the front door stand open but to not build a 34.8M-pixel image we never wanted. Note
     * {@code renderBounded} scales at render time, so the oversized raster is never allocated at all
     * - which is also why the {@code OutOfMemoryError} this used to hit is now far harder to reach.
     * <p>
     * The <em>stored</em> cover is unchanged for ordinary PDFs, which is the property that matters:
     * an A4 page renders to 1240x1754 at 150 DPI and {@code saveCoverImages} scaled that to 1000x1414
     * anyway, which is exactly what bounding produces here. It is not that small pages are untouched
     * - A4 already exceeds the box - but that the size they are scaled to is the same either way, and
     * rasterising straight to it is if anything better than downsampling to it afterwards. Only a
     * page small enough to fit within the box outright is rendered at the full DPI.
     */
    private BufferedImage renderFirstPage(PdfDocument doc) {
        try (PdfPage page = doc.page(0)) {
            return page.renderBounded(RENDER_DPI, FileService.MAX_ORIGINAL_WIDTH, FileService.MAX_ORIGINAL_HEIGHT)
                    .toBufferedImage();
        }
    }
}
