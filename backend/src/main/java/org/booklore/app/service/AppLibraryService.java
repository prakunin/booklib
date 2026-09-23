package org.booklore.app.service;

import lombok.RequiredArgsConstructor;
import org.booklore.app.dto.AppLibrarySummary;
import org.booklore.app.mapper.AppBookMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.projection.LibraryBookCountProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppLibraryService {

    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final AppBookMapper mobileBookMapper;

    // The caller resolves the user outside this transaction: resolving it can write the user's
    // default settings, which a read-only transaction would silently drop.
    @Transactional(readOnly = true)
    public List<AppLibrarySummary> getLibraries(BookLoreUser user) {
        List<LibraryEntity> libraries = findVisibleLibraries(user);
        if (libraries.isEmpty()) {
            return List.of();
        }

        List<Long> libraryIds = libraries.stream().map(LibraryEntity::getId).toList();
        Map<Long, Long> bookCounts = bookRepository.countByLibraryIds(libraryIds).stream()
                .collect(Collectors.toMap(LibraryBookCountProjection::getLibraryId, LibraryBookCountProjection::getBookCount));

        return libraries.stream()
                .map(library -> mobileBookMapper.toLibrarySummary(library, bookCounts.getOrDefault(library.getId(), 0L)))
                .toList();
    }

    private List<LibraryEntity> findVisibleLibraries(BookLoreUser user) {
        if (user.getPermissions().isAdmin()) {
            return libraryRepository.findAll();
        }
        List<Long> libraryIds = user.getAssignedLibraries() != null
                ? user.getAssignedLibraries().stream().map(Library::getId).toList()
                : List.of();
        return libraryRepository.findByIdIn(libraryIds);
    }
}
