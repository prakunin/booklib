package org.booklore.service.inpx;

import lombok.RequiredArgsConstructor;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.library.BookDeletionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InpxArchiveRemovalBatchService {

    private static final int BATCH_SIZE = 500;

    private final BookFileRepository bookFileRepository;
    private final BookDeletionService bookDeletionService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RemovalBatch removeNext(long libraryId, Set<String> queryArchives,
                                   Set<String> allMissingArchives, long afterId) {
        List<Long> candidateIds = bookFileRepository.findBookIdsWithSourceArchivesAfterId(
                libraryId, queryArchives, afterId, PageRequest.of(0, BATCH_SIZE));
        if (candidateIds.isEmpty()) {
            return RemovalBatch.EMPTY;
        }
        Map<Long, List<String>> sourcesByBookId = bookFileRepository
                .findBookFormatArchiveSourcesByBookIds(candidateIds).stream()
                .collect(Collectors.groupingBy(
                        row -> (Long) row[0],
                        Collectors.mapping(row -> (String) row[1], Collectors.toList())));
        List<Long> removableIds = candidateIds.stream()
                .filter(bookId -> isBackedOnlyByMissingArchives(
                        sourcesByBookId.get(bookId), allMissingArchives))
                .toList();
        if (!removableIds.isEmpty()) {
            bookDeletionService.deleteRemovedBooks(removableIds);
        }
        return new RemovalBatch(candidateIds.size(), removableIds.size(), candidateIds.getLast());
    }

    private boolean isBackedOnlyByMissingArchives(List<String> sources, Set<String> missingArchives) {
        return sources != null && !sources.isEmpty()
                && sources.stream().allMatch(source -> source != null && missingArchives.contains(source));
    }

    public record RemovalBatch(int scanned, int removed, long lastBookId) {
        private static final RemovalBatch EMPTY = new RemovalBatch(0, 0, 0);
    }
}
