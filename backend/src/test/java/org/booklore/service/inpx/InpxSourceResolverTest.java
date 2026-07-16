package org.booklore.service.inpx;

import org.booklore.config.security.service.LibraryAccessGuard;
import org.booklore.exception.APIException;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.LibrarySourceType;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.inpx.InpxSourceResolver.InpxSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InpxSourceResolverTest {

    private LibraryRepository libraryRepository;
    private LibraryAccessGuard libraryAccessGuard;
    private InpxSourceResolver resolver;

    @BeforeEach
    void setUp() {
        libraryRepository = mock(LibraryRepository.class);
        libraryAccessGuard = mock(LibraryAccessGuard.class);
        resolver = new InpxSourceResolver(libraryRepository, libraryAccessGuard);
    }

    @Nested
    class FromARegisteredLibrary {

        @Test
        void takesBothPathsFromTheLibraryAndIgnoresAnyClientSuppliedPath() {
            when(libraryRepository.findById(3L)).thenReturn(Optional.of(inpxLibrary("/srv/index.inpx", "/srv/archives")));

            InpxSource source = resolver.resolve(3L, "/etc/passwd", "/etc");

            assertThat(source.inpxPath()).isEqualTo("/srv/index.inpx");
            assertThat(source.archivePath()).isEqualTo("/srv/archives");
        }

        @Test
        void rejectsALibraryTheUserIsNotAssignedTo() {
            doThrow(ApiError.FORBIDDEN.createException("nope")).when(libraryAccessGuard).requireAccess(3L);

            assertThatThrownBy(() -> resolver.resolve(3L, null, null))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("nope");
            // Access is asserted before the lookup so a missing library is indistinguishable from
            // an unassigned one.
            verifyNoInteractions(libraryRepository);
        }

        @Test
        void rejectsAFilesystemLibraryAsASource() {
            LibraryEntity filesystem = LibraryEntity.builder()
                    .id(3L)
                    .sourceType(LibrarySourceType.FILESYSTEM)
                    .build();
            when(libraryRepository.findById(3L)).thenReturn(Optional.of(filesystem));

            assertThatThrownBy(() -> resolver.resolve(3L, null, null))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("is not an INPX source");
        }

        @Test
        void rejectsAnInpxLibraryWithoutAConfiguredIndex() {
            when(libraryRepository.findById(3L)).thenReturn(Optional.of(inpxLibrary("  ", null)));

            assertThatThrownBy(() -> resolver.resolve(3L, null, null))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("has no index path configured");
        }

        private LibraryEntity inpxLibrary(String inpxPath, String archivePath) {
            return LibraryEntity.builder()
                    .id(3L)
                    .sourceType(LibrarySourceType.INPX)
                    .inpxPath(inpxPath)
                    .inpxArchivePath(archivePath)
                    .build();
        }
    }

    @Nested
    class FromAManualPath {

        @Test
        void isAllowedForAdministrators() {
            InpxSource source = resolver.resolve(null, "/srv/index.inpx", "/srv/archives");

            assertThat(source.inpxPath()).isEqualTo("/srv/index.inpx");
            assertThat(source.archivePath()).isEqualTo("/srv/archives");
        }

        @Test
        void isRejectedForEveryoneElse() {
            doThrow(ApiError.FORBIDDEN.createException("admins only"))
                    .when(libraryAccessGuard).requireAdmin(org.mockito.ArgumentMatchers.anyString());

            assertThatThrownBy(() -> resolver.resolve(null, "/etc/passwd", null))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("admins only");
        }

        @Test
        void requiresEitherASourceLibraryOrAPath() {
            assertThatThrownBy(() -> resolver.resolve(null, "  ", null))
                    .isInstanceOf(APIException.class)
                    .hasMessageContaining("Either sourceLibraryId or inpxPath is required");
            verifyNoInteractions(libraryAccessGuard);
        }
    }
}
