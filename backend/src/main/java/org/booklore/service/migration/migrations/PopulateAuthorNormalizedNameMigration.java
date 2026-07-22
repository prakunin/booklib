package org.booklore.service.migration.migrations;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.AuthorReconcileStateEntity;
import org.booklore.model.enums.AuthorReconcileState;
import org.booklore.repository.AuthorReconcileStateRepository;
import org.booklore.repository.AuthorRepository;
import org.booklore.service.migration.Migration;
import org.booklore.util.AuthorNames;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Backfills {@code normalized_name} and seeds a PENDING reconcile-state row for legacy authors
 * that predate {@link AuthorEntity#computeDerivedFields()} (which now populates normalized_name
 * on every create/update). Only rows with {@code normalized_name IS NULL} are ever touched, which
 * makes this migration idempotent: a row drops out of the work set the moment it is processed,
 * so a restart (or a re-run after a crash) simply resumes on the remaining legacy rows.
 *
 * <p>Runs OUTSIDE a single wrapping transaction ({@link #runsInSingleTransaction()} is {@code
 * false}) and commits one page at a time via {@link TransactionTemplate}, so ~204k rows do not
 * accumulate into one huge undo log and a crash mid-run only loses the current in-flight page.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PopulateAuthorNormalizedNameMigration implements Migration {

    private static final int PAGE_SIZE = 1000;

    private final AuthorRepository authorRepository;
    private final AuthorReconcileStateRepository reconcileStateRepository;
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;

    @Override
    public String getKey() {
        return "populateAuthorNormalizedName";
    }

    @Override
    public String getDescription() {
        return "Populate normalized_name and seed PENDING reconcile state for all authors";
    }

    @Override
    public boolean runsInSingleTransaction() {
        return false;
    }

    @Override
    public void execute() {
        log.info("Starting migration: {}", getKey());
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        int totalProcessed = 0;
        long lastId = 0L;
        while (true) {
            long fromId = lastId;
            PageResult result = transactionTemplate.execute(status -> processPage(fromId));
            if (result == null || result.processed() == 0) {
                break;
            }
            totalProcessed += result.processed();
            lastId = result.lastId();
            log.info("Migration progress: {} authors processed", totalProcessed);
        }
        log.info("Completed migration '{}'. Total authors processed: {}", getKey(), totalProcessed);
    }

    /**
     * Loads and processes a single keyset page. Runs inside its own transaction (started by the
     * caller's {@link TransactionTemplate}), which is committed as soon as this method returns.
     */
    private PageResult processPage(long lastId) {
        List<AuthorEntity> authors = authorRepository.findUnnormalizedAfter(lastId, PageRequest.of(0, PAGE_SIZE));
        if (authors.isEmpty()) {
            return new PageResult(0, lastId);
        }

        List<Long> ids = authors.stream().map(AuthorEntity::getId).toList();
        Set<Long> alreadyHaveState = reconcileStateRepository.findExistingAuthorIds(ids);

        List<AuthorReconcileStateEntity> newStates = new ArrayList<>();
        long maxId = lastId;
        for (AuthorEntity author : authors) {
            author.setNormalizedName(AuthorNames.normalizeKey(AuthorNames.cleanDisplayName(author.getName())));
            if (!alreadyHaveState.contains(author.getId())) {
                newStates.add(AuthorReconcileStateEntity.builder()
                        .authorId(author.getId())
                        .state(AuthorReconcileState.PENDING)
                        .attemptCount(0)
                        .build());
            }
            if (author.getId() > maxId) {
                maxId = author.getId();
            }
        }

        authorRepository.saveAll(authors);
        if (!newStates.isEmpty()) {
            reconcileStateRepository.saveAll(newStates);
        }
        entityManager.flush();
        entityManager.clear();

        return new PageResult(authors.size(), maxId);
    }

    private record PageResult(int processed, long lastId) {
    }
}
