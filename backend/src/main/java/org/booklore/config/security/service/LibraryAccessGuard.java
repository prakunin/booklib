package org.booklore.config.security.service;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.springframework.stereotype.Component;

/**
 * Programmatic counterpart of {@code @CheckLibraryAccess}. The aspect behind that annotation can
 * only read a library id from a method parameter, so callers that receive the id inside a request
 * body - or that resolve a second, non-parameter library - must assert access themselves.
 */
@Component
@RequiredArgsConstructor
public class LibraryAccessGuard {

    private final AuthenticationService authenticationService;

    public void requireAccess(long libraryId) {
        if (!hasAccess(libraryId)) {
            throw ApiError.FORBIDDEN.createException("You are not authorized to access this library.");
        }
    }

    public boolean hasAccess(long libraryId) {
        BookLoreUser user = requireUser();
        if (user.getPermissions().isAdmin()) {
            return true;
        }
        return user.getAssignedLibraries() != null
                && user.getAssignedLibraries().stream().map(Library::getId).anyMatch(id -> id != null && id == libraryId);
    }

    public void requireAdmin(String action) {
        if (!requireUser().getPermissions().isAdmin()) {
            throw ApiError.FORBIDDEN.createException(action);
        }
    }

    private BookLoreUser requireUser() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user == null) {
            throw ApiError.FORBIDDEN.createException("Authentication required.");
        }
        return user;
    }
}
