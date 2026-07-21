package org.booklore.repository;

import org.booklore.model.entity.AuthorEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorEntityMappingTest extends AbstractAuthorPersistenceTest {

    @Autowired
    private AuthorRepository authorRepository;

    @Test
    void persistsRedirectAndNormalizedName() {
        AuthorEntity canonical = authorRepository.saveAndFlush(
                AuthorEntity.builder().name("James M. Ward").normalizedName("james m ward").build());
        AuthorEntity loser = authorRepository.saveAndFlush(
                AuthorEntity.builder().name("Jim Ward").normalizedName("jim ward")
                        .mergedIntoAuthorId(canonical.getId()).build());

        flushAndClear(); // force a real SELECT, not a first-level-cache hit
        AuthorEntity reloaded = authorRepository.findById(loser.getId()).orElseThrow();
        assertThat(reloaded.getMergedIntoAuthorId()).isEqualTo(canonical.getId());
        assertThat(reloaded.getNormalizedName()).isEqualTo("jim ward");
    }
}
