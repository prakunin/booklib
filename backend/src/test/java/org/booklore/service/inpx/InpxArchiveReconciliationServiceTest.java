package org.booklore.service.inpx;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InpxArchiveReconciliationServiceTest {

    @Mock
    private BookFileRepository bookFileRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private ArchiveEntryMetadataRecognizer entryMetadataRecognizer;

    @Test
    void retiresLegacyContainerOnlyAfterANestedLeafHasBeenPersisted() {
        InpxArchiveReconciliationService service = new InpxArchiveReconciliationService(
                bookFileRepository, bookRepository, entryMetadataRecognizer);
        BookEntity legacyBook = BookEntity.builder().id(10L).deleted(false).build();
        BookFileEntity legacyContainer = BookFileEntity.builder()
                .book(legacyBook).sourceArchive("outer.zip").sourceArchiveEntry("inner.zip").build();
        BookEntity leafBook = BookEntity.builder().id(11L).deleted(false).build();
        BookFileEntity nestedLeaf = BookFileEntity.builder()
                .book(leafBook).sourceArchive("outer.zip")
                .sourceArchiveEntry(NestedArchiveLocator.encode(List.of("inner.zip", "book.fb2")))
                .build();
        when(bookFileRepository.findBookFilesByArchives(7L, List.of("outer.zip")))
                .thenReturn(List.of(legacyContainer, nestedLeaf));
        when(entryMetadataRecognizer.isGenericArchive("inner.zip")).thenReturn(true);
        int retired = service.retireObsoleteGenericContainers(7L, List.of("outer.zip"));

        assertThat(retired).isOne();
        assertThat(legacyBook.getDeleted()).isTrue();
        verify(bookRepository).saveAll(List.of(legacyBook));
    }
}
