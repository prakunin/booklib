package org.booklore.service;

import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorPhotoIndexTest {

    @Mock
    private FileService fileService;

    private AuthorPhotoIndex index;

    @BeforeEach
    void setUp() {
        index = new AuthorPhotoIndex(fileService);
    }

    @Test
    void cachesTheFilesystemScanAcrossLookups() {
        when(fileService.listAuthorIdsWithThumbnail()).thenReturn(Set.of(1L, 2L));

        assertThat(index.hasPhoto(1L)).isTrue();
        assertThat(index.hasPhoto(2L)).isTrue();
        assertThat(index.hasPhoto(3L)).isFalse();
        assertThat(index.authorIdsWithPhoto()).containsExactlyInAnyOrder(1L, 2L);

        // A single directory scan backs every lookup until invalidation.
        verify(fileService, times(1)).listAuthorIdsWithThumbnail();
    }

    @Test
    void rescansAfterInvalidation() {
        when(fileService.listAuthorIdsWithThumbnail())
                .thenReturn(Set.of(1L))
                .thenReturn(Set.of(1L, 2L));

        assertThat(index.hasPhoto(2L)).isFalse();

        index.invalidate();

        assertThat(index.hasPhoto(2L)).isTrue();
        verify(fileService, times(2)).listAuthorIdsWithThumbnail();
    }
}
