package org.booklore.repository;

import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.AuthorReconcileStateEntity;
import org.booklore.model.enums.AuthorReconcileState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorReconcileStateMappingTest extends AbstractAuthorPersistenceTest {

    @Autowired
    private AuthorRepository authorRepository;
    @Autowired
    private AuthorReconcileStateRepository stateRepository;

    @Test
    void persistsPendingState() {
        AuthorEntity author = authorRepository.saveAndFlush(
                AuthorEntity.builder().name("Provisional One").normalizedName("provisional one").build());

        stateRepository.saveAndFlush(AuthorReconcileStateEntity.builder()
                .authorId(author.getId())
                .state(AuthorReconcileState.PENDING)
                .attemptCount(0)
                .build());

        flushAndClear(); // force a real SELECT, not a first-level-cache hit
        AuthorReconcileStateEntity reloaded = stateRepository.findById(author.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(AuthorReconcileState.PENDING);
        assertThat(reloaded.getAttemptCount()).isZero();
    }
}
