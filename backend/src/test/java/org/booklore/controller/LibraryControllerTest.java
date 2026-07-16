package org.booklore.controller;

import org.booklore.config.security.annotation.CheckLibraryAccess;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {libraryId} endpoint in this controller must carry @CheckLibraryAccess, because
 * authorization here is per-user library assignment, not the global canManageLibrary()
 * boolean. cancelScan previously only carried @PreAuthorize, which let a non-admin holding
 * canManageLibrary() cancel the scan of a library they are not assigned to.
 */
class LibraryControllerTest {

    @Test
    void cancelScan_enforcesPerLibraryAccessLikeTheOtherLibraryIdEndpoints() throws NoSuchMethodException {
        Method cancelScan = LibraryController.class.getMethod("cancelScan", long.class);

        CheckLibraryAccess annotation = cancelScan.getAnnotation(CheckLibraryAccess.class);

        assertThat(annotation)
                .as("cancelScan must be annotated with @CheckLibraryAccess, exactly like rescanLibrary")
                .isNotNull();
        assertThat(annotation.libraryIdParam()).isEqualTo("libraryId");
    }
}
