package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import org.booklore.config.security.service.LibraryAccessGuard;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.LibrarySourceType;
import org.booklore.repository.LibraryRepository;
import org.springframework.stereotype.Component;

/**
 * Resolves the INPX index and archive directory an import or search should read from.
 * <p>
 * The preferred source is a registered INPX library, whose paths an administrator already vetted
 * when creating it. A manually entered path is unconstrained filesystem access - there is no path
 * allowlist - so it stays administrator-only.
 */
@Component
@RequiredArgsConstructor
public class InpxSourceResolver {

    private final LibraryRepository libraryRepository;
    private final LibraryAccessGuard libraryAccessGuard;

    public InpxSource resolve(Long sourceLibraryId, String inpxPath, String archivePath) {
        if (sourceLibraryId != null) {
            return fromLibrary(sourceLibraryId);
        }

        if (inpxPath == null || inpxPath.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Either sourceLibraryId or inpxPath is required");
        }
        libraryAccessGuard.requireAdmin("Only administrators may read a manually entered INPX path.");
        return new InpxSource(inpxPath, archivePath);
    }

    private InpxSource fromLibrary(long sourceLibraryId) {
        // Checked before the lookup so an unauthorized caller cannot tell a missing library from an
        // unassigned one.
        libraryAccessGuard.requireAccess(sourceLibraryId);
        LibraryEntity source = libraryRepository.findById(sourceLibraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(sourceLibraryId));
        if (source.getSourceType() != LibrarySourceType.INPX) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "Library " + sourceLibraryId + " is not an INPX source");
        }
        if (source.getInpxPath() == null || source.getInpxPath().isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "INPX library " + sourceLibraryId + " has no index path configured");
        }
        return new InpxSource(source.getInpxPath(), source.getInpxArchivePath());
    }

    public record InpxSource(String inpxPath, String archivePath) {
    }
}
