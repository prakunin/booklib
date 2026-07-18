package org.booklore.app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.service.event.BookCatalogChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
class CatalogSummaryInvalidationListener {

    private final CatalogSummaryCache catalogSummaryCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void handle(BookCatalogChangedEvent event) {
        catalogSummaryCache.invalidateAll();
        log.debug("Invalidated app catalog summary cache after {}", event.topic());
    }
}
