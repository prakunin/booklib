package org.booklore.app.service;

import org.booklore.app.dto.AppLibrarySummary;
import org.booklore.app.mapper.AppBookMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.projection.LibraryBookCountProjection;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppLibraryServiceTest {

    @Mock private LibraryRepository libraryRepository;
    @Mock private BookRepository bookRepository;
    @Mock private AppBookMapper mobileBookMapper;

    private AppLibraryService service;

    @BeforeEach
    void setUp() {
        service = new AppLibraryService(libraryRepository, bookRepository, mobileBookMapper);
    }

    @Nested
    class GetLibraries {

        @Test
        void adminSeesAllLibrariesWithCountsFromOneGroupedQuery() {
            when(libraryRepository.findAll()).thenReturn(List.of(library(1L, "Fiction"), library(2L, "Empty")));
            when(bookRepository.countByLibraryIds(List.of(1L, 2L))).thenReturn(List.of(count(1L, 42L)));
            when(mobileBookMapper.toLibrarySummary(any(), anyLong())).thenCallRealMethod();

            List<AppLibrarySummary> result = service.getLibraries(user(true, null));

            assertThat(result)
                    .extracting(AppLibrarySummary::getName, AppLibrarySummary::getBookCount)
                    .containsExactly(
                            Tuple.tuple("Fiction", 42L),
                            Tuple.tuple("Empty", 0L));
            verify(bookRepository, never()).countByLibraryId(anyLong());
        }

        @Test
        void regularUserSeesOnlyAssignedLibraries() {
            Library assigned = Library.builder().id(7L).build();
            when(libraryRepository.findByIdIn(List.of(7L))).thenReturn(List.of(library(7L, "Mine")));
            when(bookRepository.countByLibraryIds(List.of(7L))).thenReturn(List.of(count(7L, 3L)));
            when(mobileBookMapper.toLibrarySummary(any(), anyLong())).thenCallRealMethod();

            List<AppLibrarySummary> result = service.getLibraries(user(false, List.of(assigned)));

            assertThat(result).singleElement()
                    .satisfies(summary -> {
                        assertThat(summary.getId()).isEqualTo(7L);
                        assertThat(summary.getBookCount()).isEqualTo(3L);
                    });
            verify(libraryRepository, never()).findAll();
        }

        @Test
        void userWithoutLibrariesSkipsTheCountQuery() {
            when(libraryRepository.findByIdIn(List.of())).thenReturn(List.of());

            assertThat(service.getLibraries(user(false, null))).isEmpty();
            verify(bookRepository, never()).countByLibraryIds(any());
        }
    }

    private static BookLoreUser user(boolean admin, List<Library> assignedLibraries) {
        var permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(admin);
        return BookLoreUser.builder().id(1L).permissions(permissions).assignedLibraries(assignedLibraries).build();
    }

    private static LibraryEntity library(Long id, String name) {
        return LibraryEntity.builder().id(id).name(name).build();
    }

    private static LibraryBookCountProjection count(Long libraryId, Long bookCount) {
        return new LibraryBookCountProjection() {
            @Override
            public Long getLibraryId() {
                return libraryId;
            }

            @Override
            public Long getBookCount() {
                return bookCount;
            }
        };
    }
}
