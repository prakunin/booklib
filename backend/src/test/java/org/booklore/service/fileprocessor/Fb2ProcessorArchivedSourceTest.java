package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.Fb2MetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Fb2ProcessorArchivedSourceTest {

    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock private BookCreatorService bookCreatorService;
    @Mock private BookMapper bookMapper;
    @Mock private FileService fileService;
    @Mock private MetadataMatchService metadataMatchService;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;
    @Mock private Fb2MetadataExtractor fb2MetadataExtractor;
    @Mock private ArchivedBookContentService archivedBookContentService;

    @Test
    void resolvesArchivedFb2BeforeExtractingItsCover() {
        Fb2Processor processor = new Fb2Processor(
                bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                fileService, metadataMatchService, sidecarMetadataWriter, fb2MetadataExtractor,
                archivedBookContentService);
        BookEntity book = BookEntity.builder().id(42L).build();
        BookFileEntity bookFile = BookFileEntity.builder()
                .book(book)
                .fileName("book.fb2")
                .fileSubPath("")
                .sourceArchive("catalog.zip")
                .sourceArchiveEntry("book.fb2")
                .build();
        Path cachedFile = Path.of("/tmp/inpx-cache/book.fb2");
        when(archivedBookContentService.resolve(bookFile)).thenReturn(cachedFile);

        boolean generated = processor.generateCover(book, bookFile);

        assertThat(generated).isFalse();
        verify(fb2MetadataExtractor).extractCover(new File(cachedFile.toUri()));
    }
}
