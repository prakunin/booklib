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
 * Backfills {@code normalized_name} and seeds a PENDING reconcile-state row for every author that
 * still needs one. The work set is {@code normalized_name IS NULL OR missing reconcile-state row}:
 * {@link AuthorEntity#computeDerivedFields()} (@PrePersist/@PreUpdate) populates normalized_name on
 * ANY create/update, including a plain user edit of a legacy author, but it does not create a
 * reconcile-state sidecar. Without the "missing sidecar" half of the predicate, such an author would
 * have a non-null normalized_name and would silently and permanently drop out of the work set without
 * ever getting a sidecar. Both halves of the predicate re-query on every page, so the migration
 * remains idempotent and restart-safe: a row drops out of the work set only once it has both a
 * normalized_name and a sidecar.
 *
 * <p>Updates are field-only and conditional: {@link AuthorRepository#backfillNormalizedName} is a
 * bulk {@code UPDATE ... SET normalized_name = :nn WHERE id = :id AND normalized_name IS NULL} that
 * touches exactly one column and only writes it when still null. This runs on {@code
 * ApplicationReadyEvent} while the app is already serving requests, so a naive
 * load-full-entity-then-saveAll would let Hibernate UPDATE every column from a stale in-memory
 * snapshot, clobbering a concurrent user edit (name/description/asin/lock flags) made between the
 * SELECT and the flush. The conditional, field-only UPDATE never overwrites a row a concurrent
 * request has already normalized (its WHERE clause simply matches zero rows), and never touches any
 * other column.
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
        List<AuthorRepository.AuthorBackfillView> page =
                authorRepository.findAuthorsNeedingBackfillAfter(lastId, PageRequest.of(0, PAGE_SIZE));
        if (page.isEmpty()) {
            return new PageResult(0, lastId);
        }

        List<Long> ids = page.stream().map(AuthorRepository.AuthorBackfillView::getId).toList();
        Set<Long> alreadyHaveState = reconcileStateRepository.findExistingAuthorIds(ids);

        List<AuthorReconcileStateEntity> newStates = new ArrayList<>();
        long maxId = lastId;
        for (AuthorRepository.AuthorBackfillView author : page) {
            String nn = AuthorNames.normalizeKey(AuthorNames.cleanDisplayName(author.getName()));
            // Field-only, conditional: only writes normalized_name, and only if still null, so a
            // concurrent user edit (which may have set normalized_name via @PreUpdate) is never clobbered.
            authorRepository.backfillNormalizedName(author.getId(), nn);
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
        if (!newStates.isEmpty()) {
            reconcileStateRepository.saveAll(newStates);
        }
        entityManager.flush();
        entityManager.clear();

        return new PageResult(page.size(), maxId);
    }

    private record PageResult(int processed, long lastId) {
    }
}
