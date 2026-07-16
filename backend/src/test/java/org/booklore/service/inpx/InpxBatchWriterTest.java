package org.booklore.service.inpx;

import jakarta.persistence.EntityManager;
import org.booklore.model.dto.inpx.InpxBookDto;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.AuthorRepository;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InpxBatchWriterTest {

    @Mock
    private BookRepository bookRepository;
    @Mock
    private BookFileRepository bookFileRepository;
    @Mock
    private AuthorRepository authorRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private InpxBatchWriter writer;

    private InpxScanCaches caches;

    @BeforeEach
    void setUp() {
        caches = new InpxScanCaches();
        when(entityManager.getReference(eq(LibraryEntity.class), anyLong()))
                .thenReturn(LibraryEntity.builder().id(7L).build());
        when(entityManager.getReference(eq(LibraryPathEntity.class), anyLong()))
                .thenReturn(LibraryPathEntity.builder().id(3L).build());
    }

    private InpxBookDto book(String archive, String file, String title, String author, Double rating) {
        return InpxBookDto.builder()
                .id(InpxParser.id(archive, file, "fb2"))
                .archiveName(archive)
                .fileName(file)
                .extension("fb2")
                .title(title)
                .authors(author == null ? List.of() : List.of(author))
                .genres(List.of())
                .series("")
                .seriesNumber("")
                .libraryId("1")
                .date("")
                .language("ru")
                .rating(rating)
                .build();
    }

    @Test
    void persistsNewBooksAndSkipsAlreadyImportedOnes() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{"fb2-1.zip", "already.fb2"}));

        InpxBatchWriter.BatchResult result = writer.persist(
                List.of(book("fb2-1.zip", "fresh", "Fresh", null, null),
                        book("fb2-1.zip", "already", "Already", null, null)),
                7L, 3L, caches);

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);

        ArgumentCaptor<List<BookEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(bookRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getMetadata().getTitle()).isEqualTo("Fresh");
                    assertThat(saved.getBookFiles().getFirst().getBookType()).isEqualTo(BookFileType.FB2);
                    assertThat(saved.getBookFiles().getFirst().getSourceArchive()).isEqualTo("fb2-1.zip");
                    assertThat(saved.getBookFiles().getFirst().getSourceArchiveEntry()).isEqualTo("fresh.fb2");
                });
    }

    @Test
    void storesTheRatingFromTheIndex() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());

        writer.persist(List.of(book("fb2-1.zip", "rated", "Rated", null, 5.0)), 7L, 3L, caches);

        ArgumentCaptor<List<BookEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(bookRepository).saveAll(captor.capture());
        assertThat(captor.getValue().getFirst().getMetadata().getRating()).isEqualTo(5.0);
    }

    @Test
    void reusesAnExistingAuthorAndCachesItAcrossBatches() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());
        when(authorRepository.findByName("Strugatsky Arkady"))
                .thenReturn(Optional.of(AuthorEntity.builder().id(42L).name("Strugatsky Arkady").build()));
        when(entityManager.getReference(eq(AuthorEntity.class), eq(42L)))
                .thenReturn(AuthorEntity.builder().id(42L).name("Strugatsky Arkady").build());

        writer.persist(List.of(book("fb2-1.zip", "a", "A", "Strugatsky Arkady", null)), 7L, 3L, caches);
        writer.persist(List.of(book("fb2-1.zip", "b", "B", "Strugatsky Arkady", null)), 7L, 3L, caches);

        verify(authorRepository, never()).save(any(AuthorEntity.class));
        // second batch must hit the cache, not the repository
        verify(authorRepository).findByName("Strugatsky Arkady");
        assertThat(caches.authors()).containsEntry("strugatsky arkady", 42L);
    }

    @Test
    void createsAnUnknownAuthorOnce() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());
        when(authorRepository.findByName("New Author")).thenReturn(Optional.empty());
        when(authorRepository.save(any(AuthorEntity.class)))
                .thenAnswer(invocation -> {
                    AuthorEntity saved = invocation.getArgument(0);
                    saved.setId(99L);
                    return saved;
                });
        when(entityManager.getReference(eq(AuthorEntity.class), eq(99L)))
                .thenReturn(AuthorEntity.builder().id(99L).name("New Author").build());

        writer.persist(List.of(book("fb2-1.zip", "a", "A", "New Author", null)), 7L, 3L, caches);
        writer.persist(List.of(book("fb2-1.zip", "b", "B", "New Author", null)), 7L, 3L, caches);

        verify(authorRepository).save(any(AuthorEntity.class));
        assertThat(caches.authors()).containsEntry("new author", 99L);
    }

    @Test
    void neverLoadsTheWholeAuthorOrCategoryTable() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());

        writer.persist(List.of(book("fb2-1.zip", "a", "A", null, null)), 7L, 3L, caches);

        verify(authorRepository, never()).findAll();
        verify(categoryRepository, never()).findAll();
    }

    @Test
    void skipsDuplicateArchiveKeysWithinTheSameBatch() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());

        InpxBatchWriter.BatchResult result = writer.persist(
                List.of(book("fb2-1.zip", "dup", "Dup 1", null, null),
                        book("fb2-1.zip", "dup", "Dup 2", null, null)),
                7L, 3L, caches);

        // Both DTOs share the same archive key ("fb2-1.zip|dup.fb2"); the duplicate query
        // that populates `existing` only ran once for the whole batch, so without the
        // in-batch seenInBatch guard both entries would be inserted.
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);

        ArgumentCaptor<List<BookEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(bookRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    void queriesTheRawArchiveAndEntryColumnsSoTheCompositeIndexCanBeUsed() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());

        writer.persist(
                List.of(book("fb2-1.zip", "a", "A", null, null),
                        book("fb2-2.zip", "b", "B", null, null)),
                7L, 3L, caches);

        // The duplicate lookup must query the raw sourceArchive/sourceArchiveEntry columns
        // (not CONCAT(...) of them) so MariaDB can use idx_book_file_archive_source instead of
        // scanning every book_file row for the library on every batch.
        verify(bookFileRepository).findExistingArchiveEntries(
                eq(7L), eq(Set.of("fb2-1.zip", "fb2-2.zip")), eq(Set.of("a.fb2", "b.fb2")));
    }

    @Test
    void doesNotTreatACrossProductMatchAsAnExistingKeyUnlessItIsActuallyInTheBatch() {
        // The archives/entries IN-lists can legitimately match a combination that no batch
        // record actually has (e.g. archive from one record + entry name from another). Here
        // the DB "existing" row is ("fb2-1.zip", "b.fb2"), which is a cross-product hit against
        // this batch's own distinct archives ({fb2-1.zip, fb2-2.zip}) and entries ({a.fb2,
        // b.fb2}) - but neither actual batch key ("fb2-1.zip|a.fb2", "fb2-2.zip|b.fb2") equals
        // it, so nothing should be skipped.
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{"fb2-1.zip", "b.fb2"}));

        InpxBatchWriter.BatchResult result = writer.persist(
                List.of(book("fb2-1.zip", "a", "A", null, null),
                        book("fb2-2.zip", "b", "B", null, null)),
                7L, 3L, caches);

        assertThat(result.added()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
    }

    @Test
    void reusesANewAuthorWithinTheSameBatchWithoutSavingTwice() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());
        when(authorRepository.findByName("New Author")).thenReturn(Optional.empty());
        when(authorRepository.save(any(AuthorEntity.class))).thenAnswer(invocation -> {
            AuthorEntity saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
        when(entityManager.getReference(eq(AuthorEntity.class), eq(99L)))
                .thenReturn(AuthorEntity.builder().id(99L).name("New Author").build());

        writer.persist(List.of(
                book("fb2-1.zip", "a", "A", "New Author", null),
                book("fb2-1.zip", "b", "B", "New Author", null)),
                7L, 3L, caches);

        verify(authorRepository, times(1)).save(any(AuthorEntity.class));
    }

    @Test
    void newAuthorEntersTheScanWideCacheOnlyAfterItsBatchCommits() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());
        when(authorRepository.findByName("New Author")).thenReturn(Optional.empty());
        when(authorRepository.save(any(AuthorEntity.class))).thenAnswer(invocation -> {
            AuthorEntity saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
        when(entityManager.getReference(eq(AuthorEntity.class), eq(99L)))
                .thenReturn(AuthorEntity.builder().id(99L).name("New Author").build());

        TransactionSynchronizationManager.initSynchronization();
        try {
            writer.persist(List.of(book("fb2-1.zip", "a", "A", "New Author", null)), 7L, 3L, caches);

            // Not promoted yet: the enclosing (simulated) batch transaction has not committed.
            assertThat(caches.authors()).doesNotContainKey("new author");

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(caches.authors()).containsEntry("new author", 99L);
    }

    @Test
    void newAuthorDoesNotPoisonTheScanWideCacheWhenItsBatchRollsBack() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());
        when(authorRepository.findByName("New Author")).thenReturn(Optional.empty());
        when(authorRepository.save(any(AuthorEntity.class))).thenAnswer(invocation -> {
            AuthorEntity saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
        when(entityManager.getReference(eq(AuthorEntity.class), eq(99L)))
                .thenReturn(AuthorEntity.builder().id(99L).name("New Author").build());

        TransactionSynchronizationManager.initSynchronization();
        try {
            writer.persist(List.of(book("fb2-1.zip", "a", "A", "New Author", null)), 7L, 3L, caches);

            // Simulate the enclosing batch transaction rolling back: afterCommit() is
            // never invoked, only afterCompletion(STATUS_ROLLED_BACK).
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // The id for the never-committed author must never reach the scan-wide cache:
        // a later batch reusing this cache would otherwise call entityManager.getReference
        // with a dangling id and fail at flush.
        assertThat(caches.authors()).doesNotContainKey("new author");
    }

    @Test
    void queriesTheAuthorRepositoryWithTheStrippedNameSoWhitespaceCannotDuplicateAnAuthor() {
        when(bookFileRepository.findExistingArchiveEntries(eq(7L), any(), any())).thenReturn(List.of());
        when(authorRepository.findByName("Padded Author"))
                .thenReturn(Optional.of(AuthorEntity.builder().id(11L).name("Padded Author").build()));
        when(entityManager.getReference(eq(AuthorEntity.class), eq(11L)))
                .thenReturn(AuthorEntity.builder().id(11L).name("Padded Author").build());

        writer.persist(List.of(book("fb2-1.zip", "a", "A", "  Padded Author  ", null)), 7L, 3L, caches);

        // The cache key is normalized with strip(); the repository lookup must use the
        // same stripped value, otherwise "  Padded Author  " and "Padded Author" would
        // be treated as different authors by the database.
        verify(authorRepository).findByName("Padded Author");
        verify(authorRepository, never()).findByName("  Padded Author  ");
    }
}
