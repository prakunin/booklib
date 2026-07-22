package org.booklore.service.browse;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import org.booklore.browse.SortTerm;
import org.booklore.model.entity.BookEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookSortRegistryTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void metadataSortReusesExistingInnerJoin() {
        Root<BookEntity> root = mock(Root.class);
        Join<BookEntity, ?> metadataJoin = mock(Join.class);
        Attribute<?, ?> metadataAttribute = mock(Attribute.class);
        Path<Object> seriesNumber = mock(Path.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Order expectedOrder = mock(Order.class);

        when(metadataAttribute.getName()).thenReturn("metadata");
        when(metadataJoin.getAttribute()).thenReturn((Attribute) metadataAttribute);
        when(metadataJoin.get("seriesNumber")).thenReturn(seriesNumber);
        when(root.getJoins()).thenReturn((Set) Set.of(metadataJoin));
        when(cb.asc(seriesNumber)).thenReturn(expectedOrder);

        List<Order> orders = new BookSortRegistry().registry().toOrders(
                List.of(new SortTerm("seriesNumber", false)), root, query, cb, null);

        assertThat(orders).containsExactly(expectedOrder);
        verify(root, never()).join(eq("metadata"), any(JoinType.class));
    }
}
