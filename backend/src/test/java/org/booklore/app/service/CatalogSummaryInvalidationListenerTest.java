package org.booklore.app.service;

import org.booklore.app.dto.AppCatalogSummary;
import org.booklore.model.websocket.Topic;
import org.booklore.service.event.BookCatalogChangedEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSummaryInvalidationListenerTest {

    @Test
    void handleInvalidatesCatalogSummaryCache() {
        CatalogSummaryCache cache = new CatalogSummaryCache(60);
        CatalogSummaryInvalidationListener listener = new CatalogSummaryInvalidationListener(cache);
        AtomicInteger computations = new AtomicInteger();
        Supplier<AppCatalogSummary> loader = () -> new AppCatalogSummary(
                computations.incrementAndGet(), 0, 0, 0, Map.of());

        cache.get("u1", loader);

        listener.handle(new BookCatalogChangedEvent(Topic.BOOK_UPDATE));
        AppCatalogSummary refreshed = cache.get("u1", loader);

        assertThat(refreshed.totalBooks()).isEqualTo(2);
    }
}
