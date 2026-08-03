package org.booklore.service.enrichment;

import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.enrichment.catalog.LocalCatalogIndexService;
import org.booklore.service.metadata.MetadataRefreshService;
import org.booklore.repository.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * The single place enrichment happens.
 * <p>
 * Every entry point — the button on a book, a library-wide run, the queue worker — arrives here, so
 * ordering, cost control, confidence and the write policy are decided once instead of once per
 * surface. Steps are discovered as beans and run in {@code @Order}, cheapest first, each seeing what
 * the previous ones already found.
 * <p>
 * The transaction boundaries are the load-bearing part. The book is read and detached in a short
 * read-only transaction; the steps — provider scrapes measured in seconds, the agent in minutes —
 * run holding no connection at all; the result is written in a second short transaction against a
 * freshly loaded entity. A long-running step inside a transaction would hold a pooled connection for
 * its whole duration, and on a library-wide run that exhausts the pool.
 */
@Slf4j
@Service
public class EnrichmentPipeline {

    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final List<EnrichmentStepHandler> steps;
    private final EnrichmentResolver resolver;
    private final EnrichmentApplier applier;
    private final EnrichmentFieldOptions fieldOptions;
    private final MetadataRefreshService metadataRefreshService;
    private final AppSettingService appSettingService;
    private final LocalCatalogIndexService localCatalogIndexService;
    private final TransactionTemplate readOnlyTransaction;

    public EnrichmentPipeline(BookRepository bookRepository,
                              BookMapper bookMapper,
                              List<EnrichmentStepHandler> steps,
                              EnrichmentResolver resolver,
                              EnrichmentApplier applier,
                              EnrichmentFieldOptions fieldOptions,
                              MetadataRefreshService metadataRefreshService,
                              AppSettingService appSettingService,
                              LocalCatalogIndexService localCatalogIndexService,
                              PlatformTransactionManager transactionManager) {
        this.bookRepository = bookRepository;
        this.bookMapper = bookMapper;
        this.steps = steps;
        this.resolver = resolver;
        this.applier = applier;
        this.fieldOptions = fieldOptions;
        this.metadataRefreshService = metadataRefreshService;
        this.appSettingService = appSettingService;
        this.localCatalogIndexService = localCatalogIndexService;
        // Own template rather than the shared bean: setReadOnly on the shared one would leak to
        // every other consumer of it.
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    public EnrichmentOutcome enrich(long bookId, EnrichmentRequest request) {
        EnrichmentContext context = loadContext(bookId, request);

        // Reviews and author biographies need the reverse index, which is built by a background
        // pass. Asking for it here means the first run on a fresh catalog gets descriptions (which
        // need no index) and later runs get the rest, rather than nothing until someone remembers
        // to trigger indexing.
        localCatalogIndexService.ensureIndexed(context.getLibraryId());

        for (EnrichmentStepHandler step : steps) {
            runStep(step, context);
        }

        MetadataRefreshOptions options = fieldOptions.withLocalCatalog(
                metadataRefreshService.resolveMetadataRefreshOptions(
                        context.getLibraryId(), appSettingService.getAppSettings()));
        EnrichmentOutcome outcome = resolver.resolve(context, options);
        applier.apply(context, outcome);
        return outcome;
    }

    private void runStep(EnrichmentStepHandler step, EnrichmentContext context) {
        try {
            if (!step.supports(context)) {
                return;
            }
            step.run(context);
            context.markStepRun(step.type());
        } catch (Exception e) {
            // One failing source must not cost the book everything the other sources found.
            log.warn("Enrichment step {} failed for book {}: {}", step.type(), context.bookId(), e.getMessage());
            context.note(step.type() + " failed: " + e.getMessage());
        }
    }

    private EnrichmentContext loadContext(long bookId, EnrichmentRequest request) {
        return readOnlyTransaction.execute(status -> {
            BookEntity entity = bookRepository.findByIdWithBookFiles(bookId)
                    .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
            Book book = bookMapper.toBook(entity);
            BookFileEntity archived = archivedFile(entity);
            return new EnrichmentContext(
                    book,
                    entity.getLibrary() == null ? 0L : entity.getLibrary().getId(),
                    archived == null ? null : archived.getSourceArchive(),
                    archived == null ? null : archived.getSourceArchiveEntry(),
                    request);
        });
    }

    /**
     * The file the local catalog can be keyed on. Books outside archives simply have none, and the
     * catalog steps skip themselves.
     */
    private BookFileEntity archivedFile(BookEntity entity) {
        if (entity.getBookFiles() == null) {
            return null;
        }
        return entity.getBookFiles().stream()
                .filter(file -> file.getSourceArchive() != null && file.getSourceArchiveEntry() != null)
                .findFirst()
                .orElse(null);
    }
}
