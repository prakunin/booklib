package org.booklore.service.book;

import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.request.DuplicateDetectionRequest;
import org.booklore.model.dto.response.DuplicateGroup;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.util.BookUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class DuplicateDetectionService {

    private static final List<BookFileType> DEFAULT_FORMAT_PRIORITY = List.of(
            BookFileType.EPUB, BookFileType.PDF, BookFileType.AZW3,
            BookFileType.MOBI, BookFileType.FB2, BookFileType.CBX, BookFileType.AUDIOBOOK
    );
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern UNDERSCORE_HYPHEN_PATTERN = Pattern.compile("[_\\-]");
    private static final Pattern NON_ALPHANUMERIC_PATTERN = Pattern.compile("[^a-z0-9\\s]");
    static final int DUPLICATE_DETECTION_BATCH_SIZE = 500;

    private final BookRepository bookRepository;
    private final BookMapper bookMapper;

    @Transactional(readOnly = true)
    public List<DuplicateGroup> findDuplicates(DuplicateDetectionRequest request) {
        DuplicateDetectionScan scan = loadDuplicateDetectionScan(request.libraryId());
        if (scan.bookCount() < 2) {
            return List.of();
        }

        List<BookFileType> formatPriority = scan.formatPriority();
        if (formatPriority == null || formatPriority.isEmpty()) {
            formatPriority = DEFAULT_FORMAT_PRIORITY;
        }

        Set<Long> alreadyGrouped = new HashSet<>();
        List<DuplicateGroup> groups = new ArrayList<>();

        if (request.matchByIsbn()) {
            groups.addAll(findByIsbn(request.libraryId(), alreadyGrouped, formatPriority));
        }
        if (request.matchByExternalId()) {
            groups.addAll(findByExternalId(request.libraryId(), alreadyGrouped, formatPriority));
        }
        if (request.matchByTitleAuthor()) {
            groups.addAll(findByTitleAuthor(request.libraryId(), alreadyGrouped, formatPriority));
        }
        if (request.matchByDirectory()) {
            groups.addAll(findByDirectory(request.libraryId(), alreadyGrouped, formatPriority));
        }
        if (request.matchByFilename()) {
            groups.addAll(findByFilename(request.libraryId(), alreadyGrouped, formatPriority));
        }

        return groups;
    }

    private DuplicateDetectionScan loadDuplicateDetectionScan(Long libraryId) {
        long count = 0;
        List<BookFileType> formatPriority = null;
        long afterId = 0;
        Pageable pageable = PageRequest.of(0, DUPLICATE_DETECTION_BATCH_SIZE);
        while (true) {
            List<BookEntity> batch = bookRepository.findDuplicateDetectionBatch(libraryId, afterId, pageable);
            if (batch.isEmpty()) {
                return new DuplicateDetectionScan(count, formatPriority);
            }
            count += batch.size();
            if (formatPriority == null) {
                BookEntity first = batch.getFirst();
                formatPriority = first.getLibrary() != null ? first.getLibrary().getFormatPriority() : null;
            }
            afterId = batch.getLast().getId();
        }
    }

    private void forEachDuplicateDetectionBatch(Long libraryId, Consumer<List<BookEntity>> consumer) {
        long afterId = 0;
        Pageable pageable = PageRequest.of(0, DUPLICATE_DETECTION_BATCH_SIZE);
        while (true) {
            List<BookEntity> batch = bookRepository.findDuplicateDetectionBatch(libraryId, afterId, pageable);
            if (batch.isEmpty()) {
                return;
            }
            consumer.accept(batch);
            afterId = batch.getLast().getId();
        }
    }

    private List<DuplicateGroup> findByIsbn(Long libraryId, Set<Long> alreadyGrouped, List<BookFileType> formatPriority) {
        Map<String, List<BookEntity>> isbnGroups = new HashMap<>();

        forEachDuplicateDetectionBatch(libraryId, books -> {
            for (BookEntity book : books) {
                collectIsbnGroup(book, isbnGroups, alreadyGrouped);
            }
        });

        return buildGroups(isbnGroups, "ISBN", alreadyGrouped, formatPriority);
    }

    private void collectIsbnGroup(BookEntity book, Map<String, List<BookEntity>> isbnGroups, Set<Long> alreadyGrouped) {
        if (alreadyGrouped.contains(book.getId())) return;
        BookMetadataEntity meta = book.getMetadata();
        if (meta == null) return;

        String isbn13 = meta.getIsbn13();
        if (isbn13 == null && meta.getIsbn10() != null) {
            isbn13 = BookUtils.isbn10To13(meta.getIsbn10());
        }
        if (isbn13 != null && !isbn13.isBlank()) {
            isbnGroups.computeIfAbsent(isbn13.trim(), k -> new ArrayList<>()).add(book);
        }
    }

    private List<DuplicateGroup> findByExternalId(Long libraryId, Set<Long> alreadyGrouped, List<BookFileType> formatPriority) {
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, BookEntity> bookMap = new HashMap<>();
        Map<String, Long> idToBook = new HashMap<>();

        forEachDuplicateDetectionBatch(libraryId, books -> {
            for (BookEntity book : books) {
                collectExternalIdUnion(book, alreadyGrouped, bookMap, parent, idToBook);
            }
        });

        Map<Long, List<BookEntity>> groups = new HashMap<>();
        for (BookEntity book : bookMap.values()) {
            Long root = find(parent, book.getId());
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(book);
        }

        List<DuplicateGroup> result = new ArrayList<>();
        for (List<BookEntity> group : groups.values()) {
            if (group.size() < 2) continue;
            group.forEach(b -> alreadyGrouped.add(b.getId()));
            result.add(toDuplicateGroup(group, "EXTERNAL_ID", formatPriority));
        }
        return result;
    }

    private void collectExternalIdUnion(BookEntity book, Set<Long> alreadyGrouped,
                                        Map<Long, BookEntity> bookMap, Map<Long, Long> parent,
                                        Map<String, Long> idToBook) {
        if (alreadyGrouped.contains(book.getId()) || book.getMetadata() == null) return;
        bookMap.put(book.getId(), book);
        parent.put(book.getId(), book.getId());

        for (String extId : extractExternalIds(book.getMetadata())) {
            if (idToBook.containsKey(extId)) {
                union(parent, book.getId(), idToBook.get(extId));
            } else {
                idToBook.put(extId, book.getId());
            }
        }
    }

    private List<String> extractExternalIds(BookMetadataEntity meta) {
        List<String> ids = new ArrayList<>();
        addIfPresent(ids, "goodreads:", meta.getGoodreadsId());
        addIfPresent(ids, "hardcover:", meta.getHardcoverId());
        addIfPresent(ids, "google:", meta.getGoogleId());
        addIfPresent(ids, "asin:", meta.getAsin());
        addIfPresent(ids, "audible:", meta.getAudibleId());
        addIfPresent(ids, "comicvine:", meta.getComicvineId());
        return ids;
    }

    private void addIfPresent(List<String> list, String prefix, String value) {
        if (value != null && !value.isBlank()) {
            list.add(prefix + value.trim());
        }
    }

    private Long find(Map<Long, Long> parent, Long x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    private void union(Map<Long, Long> parent, Long a, Long b) {
        Long rootA = find(parent, a);
        Long rootB = find(parent, b);
        if (!rootA.equals(rootB)) {
            parent.put(rootA, rootB);
        }
    }

    private List<DuplicateGroup> findByTitleAuthor(Long libraryId, Set<Long> alreadyGrouped, List<BookFileType> formatPriority) {
        Map<String, List<BookEntity>> titleGroups = new HashMap<>();

        forEachDuplicateDetectionBatch(libraryId, books -> {
            for (BookEntity book : books) {
                collectTitleGroup(book, titleGroups, alreadyGrouped);
            }
        });

        List<DuplicateGroup> result = new ArrayList<>();
        for (List<BookEntity> group : titleGroups.values()) {
            processTitleAuthorGroup(group, alreadyGrouped, formatPriority, result);
        }

        return result;
    }

    private void collectTitleGroup(BookEntity book, Map<String, List<BookEntity>> titleGroups, Set<Long> alreadyGrouped) {
        if (alreadyGrouped.contains(book.getId())) return;
        BookMetadataEntity meta = book.getMetadata();
        if (meta == null || meta.getTitle() == null || meta.getTitle().isBlank()) return;

        String normalizedTitle = BookUtils.normalizeForSearch(meta.getTitle());
        if (normalizedTitle != null && !normalizedTitle.isBlank()) {
            titleGroups.computeIfAbsent(normalizedTitle, k -> new ArrayList<>()).add(book);
        }
    }

    private void processTitleAuthorGroup(List<BookEntity> group, Set<Long> alreadyGrouped,
                                          List<BookFileType> formatPriority, List<DuplicateGroup> result) {
        if (group.size() < 2) return;

        List<BookEntity> withAuthors = group.stream()
                .filter(b -> b.getMetadata().getAuthors() != null && !b.getMetadata().getAuthors().isEmpty())
                .toList();

        if (withAuthors.size() < 2) return;

        Map<Long, Set<String>> normalizedAuthors = new HashMap<>();
        for (BookEntity book : withAuthors) {
            Set<String> names = book.getMetadata().getAuthors().stream()
                    .map(AuthorEntity::getName)
                    .filter(Objects::nonNull)
                    .map(BookUtils::normalizeForSearch)
                    .collect(Collectors.toSet());
            normalizedAuthors.put(book.getId(), names);
        }

        Map<Long, Long> parent = unionByAuthorOverlap(withAuthors, normalizedAuthors);

        Map<Long, List<BookEntity>> subGroups = new HashMap<>();
        for (BookEntity book : withAuthors) {
            Long root = find(parent, book.getId());
            subGroups.computeIfAbsent(root, k -> new ArrayList<>()).add(book);
        }

        for (List<BookEntity> subGroup : subGroups.values()) {
            if (subGroup.size() < 2) continue;
            subGroup.forEach(b -> alreadyGrouped.add(b.getId()));
            result.add(toDuplicateGroup(subGroup, "TITLE_AUTHOR", formatPriority));
        }
    }

    private Map<Long, Long> unionByAuthorOverlap(List<BookEntity> withAuthors,
                                                 Map<Long, Set<String>> normalizedAuthors) {
        Map<Long, Long> parent = new HashMap<>();
        for (BookEntity book : withAuthors) {
            parent.put(book.getId(), book.getId());
        }

        for (int i = 0; i < withAuthors.size(); i++) {
            for (int j = i + 1; j < withAuthors.size(); j++) {
                Set<String> authorsI = normalizedAuthors.get(withAuthors.get(i).getId());
                Set<String> authorsJ = normalizedAuthors.get(withAuthors.get(j).getId());
                boolean overlap = authorsI.stream().anyMatch(authorsJ::contains);
                if (overlap) {
                    union(parent, withAuthors.get(i).getId(), withAuthors.get(j).getId());
                }
            }
        }
        return parent;
    }

    private List<DuplicateGroup> findByDirectory(Long libraryId, Set<Long> alreadyGrouped, List<BookFileType> formatPriority) {
        Map<String, List<BookEntity>> dirGroups = new HashMap<>();

        forEachDuplicateDetectionBatch(libraryId, books -> {
            for (BookEntity book : books) {
                collectDirectoryGroup(book, dirGroups, alreadyGrouped);
            }
        });

        return buildGroups(dirGroups, "DIRECTORY", alreadyGrouped, formatPriority);
    }

    private void collectDirectoryGroup(BookEntity book, Map<String, List<BookEntity>> dirGroups, Set<Long> alreadyGrouped) {
        if (alreadyGrouped.contains(book.getId())) return;
        if (book.getLibraryPath() == null) return;

        List<BookFileEntity> bookFiles = book.getBookFiles();
        if (bookFiles == null || bookFiles.isEmpty()) return;

        BookFileEntity primary = bookFiles.stream()
                .filter(BookFileEntity::isBookFormat)
                .findFirst()
                .orElse(null);
        if (primary == null) return;

        String subPath = primary.getFileSubPath();
        if (subPath == null || subPath.isBlank()) return;

        String key = book.getLibraryPath().getId() + ":" + subPath;
        dirGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(book);
    }

    private List<DuplicateGroup> findByFilename(Long libraryId, Set<Long> alreadyGrouped, List<BookFileType> formatPriority) {
        Map<String, List<BookEntity>> nameGroups = new HashMap<>();

        forEachDuplicateDetectionBatch(libraryId, books -> {
            for (BookEntity book : books) {
                collectFilenameGroup(book, nameGroups, alreadyGrouped);
            }
        });

        return buildGroups(nameGroups, "FILENAME", alreadyGrouped, formatPriority);
    }

    private void collectFilenameGroup(BookEntity book, Map<String, List<BookEntity>> nameGroups, Set<Long> alreadyGrouped) {
        if (alreadyGrouped.contains(book.getId())) return;

        List<BookFileEntity> bookFiles = book.getBookFiles();
        if (bookFiles == null || bookFiles.isEmpty()) return;

        BookFileEntity primary = bookFiles.stream()
                .filter(BookFileEntity::isBookFormat)
                .findFirst()
                .orElse(null);
        if (primary == null || primary.getFileName() == null) return;

        String fileName = primary.getFileName();
        int dotIdx = fileName.lastIndexOf('.');
        String baseName = dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;

        String normalized = baseName.toLowerCase();
        normalized = UNDERSCORE_HYPHEN_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = NON_ALPHANUMERIC_PATTERN.matcher(normalized).replaceAll("");
        normalized = WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();

        if (!normalized.isBlank()) {
            nameGroups.computeIfAbsent(normalized, k -> new ArrayList<>()).add(book);
        }
    }

    private List<DuplicateGroup> buildGroups(Map<String, List<BookEntity>> groupMap, String reason,
                                              Set<Long> alreadyGrouped, List<BookFileType> formatPriority) {
        List<DuplicateGroup> result = new ArrayList<>();
        for (List<BookEntity> group : groupMap.values()) {
            if (group.size() < 2) continue;
            group.forEach(b -> alreadyGrouped.add(b.getId()));
            result.add(toDuplicateGroup(group, reason, formatPriority));
        }
        return result;
    }

    private DuplicateGroup toDuplicateGroup(List<BookEntity> entities, String reason, List<BookFileType> formatPriority) {
        BookEntity suggested = entities.stream()
                .max(Comparator
                        .comparingInt((BookEntity b) -> formatPriorityScore(b, formatPriority))
                        .thenComparingLong(b -> b.getBookFiles() == null ? 0 :
                                b.getBookFiles().stream().filter(BookFileEntity::isBookFormat).count())
                        .thenComparing(b -> b.getMetadataMatchScore() != null ? b.getMetadataMatchScore() : 0f)
                        .thenComparing(BookEntity::getId, Comparator.reverseOrder()))
                .orElse(entities.getFirst());

        List<Book> books = entities.stream()
                .map(bookMapper::toBook)
                .toList();

        return new DuplicateGroup(suggested.getId(), reason, books);
    }

    private int formatPriorityScore(BookEntity book, List<BookFileType> formatPriority) {
        if (book.getBookFiles() == null) {
            return 0;
        }
        int bestScore = 0;
        for (BookFileEntity file : book.getBookFiles()) {
            if (!file.isBookFormat() || file.getBookType() == null) continue;
            int index = formatPriority.indexOf(file.getBookType());
            if (index >= 0) {
                int score = formatPriority.size() - index;
                bestScore = Math.max(bestScore, score);
            }
        }
        return bestScore;
    }

    private record DuplicateDetectionScan(long bookCount, List<BookFileType> formatPriority) {
    }
}
