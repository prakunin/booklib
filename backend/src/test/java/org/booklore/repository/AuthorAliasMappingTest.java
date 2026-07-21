package org.booklore.repository;

import org.booklore.model.entity.AuthorAliasEntity;
import org.booklore.model.entity.AuthorEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorAliasMappingTest extends AbstractAuthorPersistenceTest {

    @Autowired
    private AuthorRepository authorRepository;
    @Autowired
    private AuthorAliasRepository aliasRepository;

    @Test
    void findsResolvableAliasByNormalizedForm() {
        AuthorEntity author = authorRepository.saveAndFlush(
                AuthorEntity.builder().name("James M. Ward").normalizedName("james m ward").build());
        aliasRepository.saveAndFlush(AuthorAliasEntity.builder()
                .authorId(author.getId())
                .name("Jim Ward")
                .normalizedAlias("jim ward")
                .language("und")
                .source("WIKIDATA")
                .resolvable(true)
                .build());
        aliasRepository.saveAndFlush(AuthorAliasEntity.builder()
                .authorId(author.getId())
                .name("J. Ward")
                .normalizedAlias("j ward")
                .language("und")
                .source("WIKIDATA")
                .resolvable(false)          // not resolvable -> excluded
                .build());

        flushAndClear(); // force real SELECTs, not first-level-cache hits
        List<AuthorAliasEntity> hits = aliasRepository.findByNormalizedAliasAndResolvableTrue("jim ward");
        assertThat(hits).extracting(AuthorAliasEntity::getAuthorId).containsExactly(author.getId());
        assertThat(aliasRepository.findByNormalizedAliasAndResolvableTrue("j ward")).isEmpty();
    }
}
