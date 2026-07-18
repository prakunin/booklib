package org.booklore.service.browse;

import org.booklore.browse.SortTerm;
import org.booklore.model.entity.BookEntity;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

public final class BookSortSpecifications {

    private BookSortSpecifications() {
    }

    public static Specification<BookEntity> withSort(
            Specification<BookEntity> filter,
            BookSortRegistry sortRegistry,
            List<SortTerm> sortTerms,
            Long userId) {
        return (root, query, cb) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                query.orderBy(sortRegistry.registry().toOrders(sortTerms, root, query, cb, userId));
            }
            return filter.toPredicate(root, query, cb);
        };
    }
}
