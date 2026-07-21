package org.booklore.service.migration.migrations;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.AuthorReconcileStateEntity;
import org.booklore.model.enums.AuthorReconcileState;
import org.booklore.repository.AuthorReconcileStateRepository;
import org.booklore.repository.AuthorRepository;
import org.booklore.service.migration.Migration;
import org.booklore.util.AuthorNames;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PopulateAuthorNormalizedNameMigration implements Migration {

    private final AuthorRepository authorRepository;
    private final AuthorReconcileStateRepository reconcileStateRepository;
    private final EntityManager entityManager;

    @Override
    public String getKey() {
        return "populateAuthorNormalizedName";
    }

    @Override
    public String getDescription() {
        return "Populate normalized_name and seed PENDING reconcile state for all authors";
    }

    @Override
    public void execute() {
        log.info("Starting migration: {}", getKey());
        int batchSize = 1000;
        int processed = 0;
        int page = 0;
        boolean morePages = true;
        while (morePages) {
            var authors = authorRepository.findAll(PageRequest.of(page, batchSize, Sort.by("id"))).getContent();
            if (authors.isEmpty()) {
                morePages = false;
            } else {
                for (var author : authors) {
                    author.setNormalizedName(AuthorNames.normalizeKey(AuthorNames.cleanDisplayName(author.getName())));
                    if (!reconcileStateRepository.existsById(author.getId())) {
                        reconcileStateRepository.save(AuthorReconcileStateEntity.builder()
                                .authorId(author.getId())
                                .state(AuthorReconcileState.PENDING)
                                .attemptCount(0)
                                .build());
                    }
                }
                authorRepository.saveAll(authors);
                processed += authors.size();
                log.info("Migration progress: {} authors processed", processed);
                entityManager.flush();
                entityManager.clear();
                morePages = authors.size() >= batchSize;
                if (morePages) {
                    page++;
                }
            }
        }
        log.info("Completed migration '{}'. Total authors processed: {}", getKey(), processed);
    }
}
