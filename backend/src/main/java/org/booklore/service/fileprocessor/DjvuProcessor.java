package org.booklore.service.fileprocessor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
import org.booklore.service.djvu.DjvuDocumentInfo;
import org.booklore.service.djvu.DjvuToolException;
import org.booklore.service.djvu.DjvuToolRunner;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.DjvuMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.BookCoverUtils;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.booklore.util.FileService.truncate;

/**
 * Scanned documents. This stage only gets them into the catalog - before it, a {@code .djvu} in a
 * watched folder did not fail to open, it did not exist at all, because {@code BookFileExtension}
 * did not recognise the extension and the scan skipped the file without a word.
 * <p>
 * DjVu files carry almost nothing about themselves, so what this reads is thin on purpose: a cover
 * rendered from page one, a page count, and whatever the rare annotation chunk happens to hold.
 * Everything else is left blank for the filename baseline and then Smart Enrichment, which knows
 * more about a book than a scanner ever wrote into it.
 */
@Slf4j
@Service
public class DjvuProcessor extends AbstractFileProcessor implements BookFileProcessor {

    private final DjvuMetadataExtractor djvuMetadataExtractor;
    private final DjvuToolRunner toolRunner;

    public DjvuProcessor(BookRepository bookRepository,
                         BookAdditionalFileRepository bookAdditionalFileRepository,
                         BookCreatorService bookCreatorService,
                         BookMapper bookMapper,
                         FileService fileService,
                         MetadataMatchService metadataMatchService,
                         SidecarMetadataWriter sidecarMetadataWriter,
                         DjvuMetadataExtractor djvuMetadataExtractor,
                         DjvuToolRunner toolRunner) {
        super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService,
                metadataMatchService, sidecarMetadataWriter);
        this.djvuMetadataExtractor = djvuMetadataExtractor;
        this.toolRunner = toolRunner;
    }

    /**
     * A decoder failure must not cost the book its place in the catalog: a shell book with the
     * filename as its title is worth far more to the user than a file that silently never appeared,
     * and Smart Enrichment can still fill it in from the providers afterwards. This is the same
     * contract {@code DocProcessor} keeps for an unparseable Word document.
     */
    @Override
    public BookEntity processNewFile(LibraryFile libraryFile) {
        BookEntity bookEntity = bookCreatorService.createShellBook(libraryFile, BookFileType.DJVU);

        if (generateCover(bookEntity)) {
            FileService.setBookCoverPath(bookEntity.getMetadata());
            bookEntity.setBookCoverHash(BookCoverUtils.generateCoverHash());
        }
        setMetadata(bookEntity);

        return bookEntity;
    }

    @Override
    public boolean generateCover(BookEntity bookEntity) {
        return generateCover(bookEntity, bookEntity.getPrimaryBookFile());
    }

    @Override
    public boolean generateCover(BookEntity bookEntity, BookFileEntity bookFile) {
        CoverExtraction extraction = extractCover(bookEntity, bookFile);
        if (extraction.outcome() != CoverProbeOutcome.COVER_FOUND) {
            return false;
        }
        return fileService.saveCoverImageFromBytes(bookEntity.getId(), extraction.data()) == CoverSaveOutcome.SAVED;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Like a PDF, a DjVu file has no embedded cover to be missing - the cover <em>is</em> page one -
     * so this processor can never honestly report a clean miss. Its outcomes are only
     * {@code COVER_FOUND} or {@code READ_FAILED}.
     */
    @Override
    public CoverExtraction extractCover(BookEntity bookEntity, BookFileEntity bookFile) {
        File file = FileUtils.getBookFullPath(bookEntity, bookFile).toFile();
        try {
            return CoverExtraction.found(djvuMetadataExtractor.extractCover(file));
        } catch (Exception e) {
            log.warn("Failed to extract cover from '{}': {}", bookFile.getFileName(), e.getMessage());
            return CoverExtraction.readFailed();
        }
    }

    @Override
    public List<BookFileType> getSupportedTypes() {
        return List.of(BookFileType.DJVU);
    }

    /**
     * The filename title is set first and only then improved on. Anything the decoder does - return
     * nothing, or fail outright - leaves a titled book in the catalog rather than an entity whose
     * metadata was half written when the exception arrived.
     */
    private void setMetadata(BookEntity bookEntity) {
        BookMetadataEntity metadata = bookEntity.getMetadata();
        metadata.setTitle(filenameTitle(bookEntity));

        File file = FileUtils.getBookFullPath(bookEntity).toFile();
        try {
            applyExtractedMetadata(file, bookEntity, metadata);
        } catch (Exception e) {
            log.warn("Failed to read DjVu metadata for '{}': {}",
                    bookEntity.getPrimaryBookFile().getFileName(), e.getMessage());
        }

        setPageCount(file, metadata);
    }

    private void applyExtractedMetadata(File file, BookEntity bookEntity, BookMetadataEntity metadata) {
        BookMetadata extracted = djvuMetadataExtractor.extractMetadata(file);
        if (extracted != null) {
            String title = truncate(extracted.getTitle(), 1000);
            if (StringUtils.isNotBlank(title)) {
                metadata.setTitle(title);
            }
            if (extracted.getAuthors() != null && !extracted.getAuthors().isEmpty()) {
                bookCreatorService.addAuthorsToBook(Set.copyOf(extracted.getAuthors()), bookEntity);
            }
            if (StringUtils.isNotBlank(extracted.getPublisher())) {
                metadata.setPublisher(extracted.getPublisher());
            }
            if (StringUtils.isNotBlank(extracted.getLanguage())) {
                metadata.setLanguage(truncate(extracted.getLanguage(), 10));
            }
            if (extracted.getPublishedDate() != null) {
                metadata.setPublishedDate(extracted.getPublishedDate());
            }
        }
    }

    /**
     * Stored at ingest so that opening the book does not have to probe the file for something the
     * catalog could already have shown.
     */
    private void setPageCount(File file, BookMetadataEntity metadata) {
        try {
            DjvuDocumentInfo info = toolRunner.probe(file.toPath());
            if (info.pageCount() > 0) {
                metadata.setPageCount(info.pageCount());
            }
        } catch (DjvuToolException e) {
            log.warn("Could not read the page count of '{}': {}", file.getName(), e.getMessage());
        }
    }

    private String filenameTitle(BookEntity bookEntity) {
        String fileName = bookEntity.getPrimaryBookFile().getFileName();
        int dot = fileName.lastIndexOf('.');
        return truncate(dot > 0 ? fileName.substring(0, dot) : fileName, 1000);
    }
}
