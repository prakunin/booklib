package org.booklore.app.specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.booklore.model.entity.BookEntity;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppBookSpecificationTest {

    @Test
    void emptyAccessibleLibrarySetMatchesNothing() {
        Root<BookEntity> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate disjunction = mock(Predicate.class);
        when(cb.disjunction()).thenReturn(disjunction);

        Predicate result = AppBookSpecification.inLibraries(Collections.emptySet())
                .toPredicate(root, query, cb);

        assertThat(result).isSameAs(disjunction);
    }

    @Test
    void nullLibrarySetKeepsAdminCatalogUnrestricted() {
        Root<BookEntity> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        Predicate result = AppBookSpecification.inLibraries(null)
                .toPredicate(root, query, cb);

        assertThat(result).isSameAs(conjunction);
    }
}
