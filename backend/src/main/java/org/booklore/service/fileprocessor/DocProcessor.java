package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.CoverExtraction;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.DocMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.booklore.util.FileService.truncate;

/**
 * Word documents. This stage only gets them into the catalog - before it, a {@code .doc} in a
 * watched folder did not fail to open, it did not exist at all, because {@code BookFileExtension}
 * did not recognise the extension and the scan skipped the file without a word.
 */
@Slf4j
@Service
public class DocProcessor extends AbstractFileProcessor implements BookFileProcessor {

    private final DocMetadataExtractor docMetadataExtractor;

    public DocProcessor(BookRepository bookRepository,
                        BookAdditionalFileRepository bookAdditionalFileRepository,
                        BookCreatorService bookCreatorService,
                        BookMapper bookMapper,
                        FileService fileService,
                        MetadataMatchService metadataMatchService,
                        SidecarMetadataWriter sidecarMetadataWriter,
                        DocMetadataExtractor docMetadataExtractor) {
        super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService,
                metadataMatchService, sidecarMetadataWriter);
        this.docMetadataExtractor = docMetadataExtractor;
    }

    @Override
    public BookEntity processNewFile(LibraryFile libraryFile) {
        BookEntity bookEntity = bookCreatorService.createShellBook(libraryFile, BookFileType.DOC);
        setBookMetadata(bookEntity);
        return bookEntity;
    }

    /**
     * Fills in what the document says about itself. A document that cannot be parsed - encrypted,
     * password-protected or malformed - simply yields nothing here and keeps the filename baseline:
     * the extractor reports the failure by returning null, and the book still reaches the catalog
     * rather than failing the scan of its siblings.
     */
    private void setBookMetadata(BookEntity bookEntity) {
        File file = bookEntity.getFullFilePath().toFile();
        BookMetadata extracted = docMetadataExtractor.extractMetadata(file);
        BookMetadataEntity metadata = bookEntity.getMetadata();

        String title = extracted == null ? null : truncate(extracted.getTitle(), 1000);
        metadata.setTitle(StringUtils.isBlank(title) ? filenameTitle(bookEntity) : title);

        if (extracted != null) {
            metadata.setPublishedDate(extracted.getPublishedDate());
            if (extracted.getAuthors() != null && !extracted.getAuthors().isEmpty()) {
                bookCreatorService.addAuthorsToBook(Set.copyOf(extracted.getAuthors()), bookEntity);
            }
        }
    }

    private String filenameTitle(BookEntity bookEntity) {
        String fileName = bookEntity.getPrimaryBookFile().getFileName();
        int dot = fileName.lastIndexOf('.');
        return truncate(dot > 0 ? fileName.substring(0, dot) : fileName, 1000);
    }

    @Override
    public boolean generateCover(BookEntity bookEntity) {
        return false;
    }

    @Override
    public CoverExtraction extractCover(BookEntity bookEntity, BookFileEntity bookFile) {
        return CoverExtraction.noCoverFound();
    }

    @Override
    public List<BookFileType> getSupportedTypes() {
        return List.of(BookFileType.DOC);
    }
}
