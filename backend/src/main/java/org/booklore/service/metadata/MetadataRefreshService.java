package org.booklore.service.metadata;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.*;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.dto.request.MetadataRefreshRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.util.BookUtils;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.MetadataFetchJobEntity;
import org.booklore.model.entity.MetadataFetchProposalEntity;
import org.booklore.model.enums.FetchedMetadataProposalStatus;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.MetadataFetchJobRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.parser.BookParser;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.booklore.model.enums.MetadataProvider.*;

@Slf4j
@AllArgsConstructor
@Service
public class MetadataRefreshService {

    private static final MetadataMerger METADATA_MERGER = new MetadataMerger();

    private final LibraryRepository libraryRepository;
    private final MetadataFetchJobRepository metadataFetchJobRepository;
    private final BookMapper bookMapper;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final NotificationService notificationService;
    private final AppSettingService appSettingService;
    private final Map<MetadataProvider, BookParser> parserMap;
    private final ObjectMapper objectMapper;
    private final BookRepository bookRepository;
    private final PlatformTransactionManager transactionManager;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;


    public void refreshMetadata(MetadataRefreshRequest request, String jobId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user != null ? user.getId() : null;
        Set<Long> actualBookIds = Collections.emptySet();
        final int totalBooks;
        try {
            AppSettings appSettings = appSettingService.getAppSettings();

            final boolean isLibraryRefresh = request.getRefreshType() == MetadataRefreshRequest.RefreshType.LIBRARY;
            final MetadataRefreshOptions requestRefreshOptions = request.getRefreshOptions();

            final boolean useRequestOptions = requestRefreshOptions != null;
            final MetadataRefreshOptions libraryRefreshOptions = !useRequestOptions && isLibraryRefresh ? resolveMetadataRefreshOptions(request.getLibraryId(), appSettings) : null;
            final List<MetadataProvider> fixedProviders = resolveFixedProviders(useRequestOptions, requestRefreshOptions, isLibraryRefresh, libraryRefreshOptions);

            actualBookIds = getBookEntities(request);
            totalBooks = actualBookIds.size();

            MetadataRefreshOptions reviewModeOptions = resolveReviewModeOptions(requestRefreshOptions, libraryRefreshOptions, appSettings);
            boolean isReviewMode = Boolean.TRUE.equals(reviewModeOptions.getReviewBeforeApply());

            MetadataFetchJobEntity task = MetadataFetchJobEntity.builder()
                    .taskId(jobId)
                    .userId(userId)
                    .status(MetadataFetchTaskStatus.IN_PROGRESS)
                    .startedAt(Instant.now())
                    .totalBooksCount(totalBooks)
                    .completedBooks(0)
                    .build();
            metadataFetchJobRepository.save(task);

            RefreshExecution execution = new RefreshExecution(useRequestOptions, isLibraryRefresh, requestRefreshOptions,
                    libraryRefreshOptions, fixedProviders, appSettings, jobId, totalBooks, isReviewMode);

            BatchResult result = processBooks(actualBookIds, execution, task);
            if (result.cancelled()) {
                return;
            }

            completeTask(task, result.completedCount(), totalBooks, isReviewMode);
            cancellationManager.clearCancellation(jobId);
            log.info("Metadata refresh task {} completed successfully", jobId);

        } catch (RuntimeException e) {
            cancellationManager.clearCancellation(jobId);
            if (e.getCause() instanceof InterruptedException) {
                log.info("Metadata refresh task {} cancelled successfully", jobId);
                return;
            }
            log.error("Fatal error during metadata refresh", e);
            int totalBooksForError = 0;
            sendBatchProgressNotification(jobId, 0, totalBooksForError, "Fatal error during metadata refresh: " + e.getMessage(), MetadataFetchTaskStatus.ERROR, false);
            throw e;
        } catch (Exception fatal) {
            cancellationManager.clearCancellation(jobId);
            log.error("Fatal error during metadata refresh", fatal);
            int totalBooksForError = actualBookIds.size();
            sendBatchProgressNotification(jobId, 0, totalBooksForError, "Fatal error during metadata refresh: " + fatal.getMessage(), MetadataFetchTaskStatus.ERROR, false);
            throw fatal;
        }
    }

    private List<MetadataProvider> resolveFixedProviders(boolean useRequestOptions, MetadataRefreshOptions requestRefreshOptions,
                                                         boolean isLibraryRefresh, MetadataRefreshOptions libraryRefreshOptions) {
        if (useRequestOptions) {
            return prepareProviders(requestRefreshOptions);
        } else if (isLibraryRefresh) {
            return prepareProviders(libraryRefreshOptions);
        }
        // Neither branch applies: processSingleBook falls back to resolving providers itself
        // per book and never reads this value, so an empty list is a safe, unused placeholder.
        return List.of();
    }

    private MetadataRefreshOptions resolveReviewModeOptions(MetadataRefreshOptions requestRefreshOptions,
                                                            MetadataRefreshOptions libraryRefreshOptions, AppSettings appSettings) {
        if (requestRefreshOptions != null) {
            return requestRefreshOptions;
        } else if (libraryRefreshOptions != null) {
            return libraryRefreshOptions;
        }
        return appSettings.getDefaultMetadataRefreshOptions();
    }

    private BatchResult processBooks(Set<Long> actualBookIds, RefreshExecution execution, MetadataFetchJobEntity task) {
        String jobId = execution.jobId();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        int completedCount = 0;

        for (Long bookId : actualBookIds) {
            if (cancellationManager.isTaskCancelled(jobId)) {
                log.info("RefreshMetadataTask {} was cancelled, stopping execution", jobId);
                cancelTask(task);
                cancellationManager.clearCancellation(jobId);
                return new BatchResult(completedCount, true);
            }

            int finalCompletedCount = completedCount;
            txTemplate.execute(status -> {
                BookEntity book = loadBookForRefresh(bookId);
                if (processSingleBook(status, book, execution, task, finalCompletedCount)) {
                    bookRepository.saveAndFlush(book);
                }
                return null;
            });
            completedCount++;
        }

        return new BatchResult(completedCount, false);
    }

    private BookEntity loadBookForRefresh(Long bookId) {
        return bookRepository.findAllWithMetadataByIds(Collections.singleton(bookId))
                .stream().findFirst()
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
    }

    private boolean processSingleBook(TransactionStatus status, BookEntity book, RefreshExecution execution,
                                      MetadataFetchJobEntity task, int finalCompletedCount) {
        String jobId = execution.jobId();
        int totalBooks = execution.totalBooks();
        boolean isReviewMode = execution.isReviewMode();
        try {
            if (book.getMetadata().areAllFieldsLocked()) {
                log.info("Skipping locked book: {}", getBookIdentifier(book));
                sendBatchProgressNotification(jobId, finalCompletedCount, totalBooks, "Skipped locked book: " + book.getMetadata().getTitle(), MetadataFetchTaskStatus.IN_PROGRESS, isReviewMode);
                return false;
            }

            MetadataRefreshOptions refreshOptions;
            List<MetadataProvider> providers;

            if (execution.useRequestOptions()) {
                refreshOptions = execution.requestRefreshOptions();
                providers = execution.fixedProviders();
            } else if (execution.isLibraryRefresh()) {
                refreshOptions = execution.libraryRefreshOptions();
                providers = execution.fixedProviders();
            } else {
                refreshOptions = resolveMetadataRefreshOptions(book.getLibrary().getId(), execution.appSettings());
                providers = prepareProviders(refreshOptions);
            }

            reportProgressIfNeeded(task, jobId, finalCompletedCount, totalBooks, book, isReviewMode);
            Map<MetadataProvider, BookMetadata> metadataMap = fetchMetadataForBook(providers, book);
            if (providers.contains(GoodReads) && !sleepBeforeGoodreadsFetch()) {
                status.setRollbackOnly();
                return false;
            }
            BookMetadata fetched = null;
            boolean bookReviewMode = false;
            if (refreshOptions != null) {
                fetched = buildFetchMetadata(bookMapper.toBook(book).getMetadata(), book.getId(), refreshOptions, metadataMap);
                bookReviewMode = Boolean.TRUE.equals(refreshOptions.getReviewBeforeApply());
            }

            applyFetchedMetadata(task, book, fetched, refreshOptions, bookReviewMode);

            sendBatchProgressNotification(jobId, finalCompletedCount + 1, totalBooks, "Processed: " + book.getMetadata().getTitle(), MetadataFetchTaskStatus.IN_PROGRESS, bookReviewMode);
            return true;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Processing interrupted for book: {}", getBookIdentifier(book));
                status.setRollbackOnly();
                return false;
            }
            log.error("Metadata update failed for book: {}", getBookIdentifier(book), e);
            sendBatchProgressNotification(jobId, finalCompletedCount, totalBooks, String.format("Failed to process: %s - %s", book.getMetadata().getTitle(), e.getMessage()), MetadataFetchTaskStatus.ERROR, isReviewMode);
            return true;
        }
    }

    private void applyFetchedMetadata(MetadataFetchJobEntity task, BookEntity book, BookMetadata fetched,
                                      MetadataRefreshOptions refreshOptions, boolean bookReviewMode) throws JacksonException {
        if (bookReviewMode) {
            saveProposal(task, book.getId(), fetched);
        } else {
            // Use the replaceMode from options - allows user to control whether to replace existing or only fill missing
            MetadataReplaceMode replaceMode = refreshOptions.getReplaceMode() != null
                    ? refreshOptions.getReplaceMode()
                    : MetadataReplaceMode.REPLACE_MISSING;
            updateBookMetadata(book, fetched, refreshOptions.isRefreshCovers(), refreshOptions.isMergeCategories(), replaceMode);
        }
    }

    private record RefreshExecution(
            boolean useRequestOptions,
            boolean isLibraryRefresh,
            MetadataRefreshOptions requestRefreshOptions,
            MetadataRefreshOptions libraryRefreshOptions,
            List<MetadataProvider> fixedProviders,
            AppSettings appSettings,
            String jobId,
            int totalBooks,
            boolean isReviewMode) {
    }

    private record BatchResult(int completedCount, boolean cancelled) {
    }

    /**
     * Sleeps a short randomized delay before hitting GoodReads (rate-limit courtesy). Returns
     * {@code false} if interrupted while sleeping, preserving the interrupt flag, so the caller
     * can roll back the current transaction and bail out.
     */
    private boolean sleepBeforeGoodreadsFetch() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(500, 1500));
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    MetadataRefreshOptions resolveMetadataRefreshOptions(Long libraryId, AppSettings appSettings) {
        MetadataRefreshOptions defaultOptions = appSettings.getDefaultMetadataRefreshOptions();
        List<MetadataRefreshOptions> libraryOptions = appSettings.getLibraryMetadataRefreshOptions();

        if (libraryId != null && libraryOptions != null) {
            return libraryOptions.stream()
                    .filter(options -> libraryId.equals(options.getLibraryId()))
                    .findFirst()
                    .orElse(defaultOptions);
        }

        return defaultOptions;
    }

    public Map<MetadataProvider, BookMetadata> fetchMetadataForBook(List<MetadataProvider> providers, Book book) {
        return providers.stream()
                .map(provider -> fetchTopMetadataFromAProvider(provider, book))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        BookMetadata::getProvider,
                        metadata -> metadata,
                        (existing, replacement) -> existing
                ));
    }

    public Map<MetadataProvider, BookMetadata> fetchMetadataForBook(List<MetadataProvider> providers, BookEntity bookEntity) {
        Book book = bookMapper.toBook(bookEntity);
        return providers.stream()
                .map(provider -> fetchTopMetadataFromAProvider(provider, book))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        BookMetadata::getProvider,
                        metadata -> metadata,
                        (existing, replacement) -> existing
                ));
    }

    private void reportProgressIfNeeded(MetadataFetchJobEntity task, String taskId, int completedCount, int total, BookEntity book, boolean isReviewMode) {
        if (task == null) return;
        task.setCompletedBooks(completedCount);
        metadataFetchJobRepository.save(task);
        String message = String.format("Processing '%s'", book.getMetadata().getTitle());
        sendBatchProgressNotification(taskId, completedCount, total, message, MetadataFetchTaskStatus.IN_PROGRESS, isReviewMode);
    }

    private String getBookIdentifier(BookEntity book) {
        if (book.getPrimaryBookFile() != null && book.getPrimaryBookFile().getFileName() != null) {
            return book.getPrimaryBookFile().getFileName();
        }
        if (book.getMetadata() != null && book.getMetadata().getTitle() != null) {
            return book.getMetadata().getTitle();
        }
        return "Book ID: " + book.getId();
    }

    private void sendBatchProgressNotification(String taskId, int current, int total, String message, MetadataFetchTaskStatus status, boolean isReview) {
        notificationService.sendMessage(Topic.BOOK_METADATA_BATCH_PROGRESS, new MetadataBatchProgressNotification(taskId, current, total, message, status.name(), isReview));
    }

    private void completeTask(MetadataFetchJobEntity task, int completed, int total, boolean isReviewMode) {
        task.setStatus(MetadataFetchTaskStatus.COMPLETED);
        task.setCompletedAt(Instant.now());
        task.setCompletedBooks(completed);
        metadataFetchJobRepository.save(task);
        sendBatchProgressNotification(task.getTaskId(), completed, total, "Batch metadata fetch successfully completed!", MetadataFetchTaskStatus.COMPLETED, isReviewMode);
    }

    private void cancelTask(MetadataFetchJobEntity task) {
        task.setStatus(MetadataFetchTaskStatus.CANCELLED);
        task.setCompletedAt(Instant.now());
        metadataFetchJobRepository.save(task);
        sendBatchProgressNotification(task.getTaskId(), task.getCompletedBooks(), task.getTotalBooksCount(), "Task cancelled by user", MetadataFetchTaskStatus.CANCELLED, false);
    }

    private void saveProposal(MetadataFetchJobEntity job, Long bookId, BookMetadata metadata) throws JacksonException {
        MetadataFetchProposalEntity proposal = MetadataFetchProposalEntity.builder()
                .job(job)
                .bookId(bookId)
                .metadataJson(objectMapper.writeValueAsString(metadata))
                .status(FetchedMetadataProposalStatus.FETCHED)
                .fetchedAt(Instant.now())
                .build();
        job.getProposals().add(proposal);
    }


    public void updateBookMetadata(BookEntity bookEntity, BookMetadata metadata, boolean replaceCover, boolean mergeCategories) {
        updateBookMetadata(bookEntity, metadata, replaceCover, mergeCategories, MetadataReplaceMode.REPLACE_MISSING);
    }

    public void updateBookMetadata(BookEntity bookEntity, BookMetadata metadata, boolean replaceCover, boolean mergeCategories, MetadataReplaceMode replaceMode) {
        MetadataUpdateContext context = MetadataUpdateContext.builder()
                .bookEntity(bookEntity)
                .metadataUpdateWrapper(MetadataUpdateWrapper.builder()
                        .metadata(metadata)
                        .build())
                .updateThumbnail(replaceCover)
                .mergeCategories(mergeCategories)
                .replaceMode(replaceMode)
                .mergeMoods(true)
                .mergeTags(true)
                .build();

        updateBookMetadata(context);
    }

    public void updateBookMetadata(MetadataUpdateContext context) {
        if (context.getMetadataUpdateWrapper() != null && context.getMetadataUpdateWrapper().getMetadata() != null) {
            bookMetadataUpdater.setBookMetadata(context);

            Book book = bookMapper.toBookWithDescription(context.getBookEntity(), true);
            
            BookLoreUser user = authenticationService.getAuthenticatedUser();
            if (user != null && book.getShelves() != null) {
                book.setShelves(filterShelvesByUserId(book.getShelves(), user.getId()));
            }
            
            notificationService.sendMessage(Topic.BOOK_METADATA_UPDATE, book);
        }
    }

    public List<MetadataProvider> prepareProviders(MetadataRefreshOptions refreshOptions) {
        AppSettings appSettings = appSettingService.getAppSettings();
        Set<MetadataProvider> allProviders = EnumSet.noneOf(MetadataProvider.class);
        allProviders.addAll(getAllProvidersUsingIndividualFields(refreshOptions, appSettings));
        return new ArrayList<>(allProviders);
    }

    protected Set<MetadataProvider> getAllProvidersUsingIndividualFields(MetadataRefreshOptions refreshOptions, AppSettings appSettings) {
        MetadataRefreshOptions.FieldOptions fieldOptions = refreshOptions.getFieldOptions();
        Set<MetadataProvider> uniqueProviders = EnumSet.noneOf(MetadataProvider.class);

        METADATA_MERGER.configuredFieldProviders(fieldOptions)
                .forEach(fieldProvider -> addProviderToSet(fieldProvider, uniqueProviders, appSettings));

        return uniqueProviders;
    }

    protected void addProviderToSet(MetadataRefreshOptions.FieldProvider fieldProvider, Set<MetadataProvider> providerSet, AppSettings appSettings) {
        if (fieldProvider != null) {
            if (fieldProvider.getP1() != null && isProviderEnabled(fieldProvider.getP1(), appSettings)) providerSet.add(fieldProvider.getP1());
            if (fieldProvider.getP2() != null && isProviderEnabled(fieldProvider.getP2(), appSettings)) providerSet.add(fieldProvider.getP2());
            if (fieldProvider.getP3() != null && isProviderEnabled(fieldProvider.getP3(), appSettings)) providerSet.add(fieldProvider.getP3());
            if (fieldProvider.getP4() != null && isProviderEnabled(fieldProvider.getP4(), appSettings)) providerSet.add(fieldProvider.getP4());
        }
    }

    protected boolean isProviderEnabled(MetadataProvider provider, AppSettings appSettings) {
        if (provider == null || appSettings == null || appSettings.getMetadataProviderSettings() == null) {
            return true;
        }

        var settings = appSettings.getMetadataProviderSettings();
        return switch (provider) {
            case Amazon -> settings.getAmazon() != null && settings.getAmazon().isEnabled();
            case Google -> settings.getGoogle() != null && settings.getGoogle().isEnabled();
            case GoodReads -> settings.getGoodReads() != null && settings.getGoodReads().isEnabled();
            case Hardcover -> settings.getHardcover() != null && settings.getHardcover().isEnabled();
            case Comicvine -> settings.getComicvine() != null && settings.getComicvine().isEnabled();
            case Ranobedb -> settings.getRanobedb() != null && settings.getRanobedb().isEnabled();
            case Douban -> settings.getDouban() != null && settings.getDouban().isEnabled();
            case Lubimyczytac -> settings.getLubimyczytac() != null && settings.getLubimyczytac().isEnabled();
            case Audible -> settings.getAudible() != null && settings.getAudible().isEnabled();
            default -> true;
        };
    }

    public BookMetadata fetchTopMetadataFromAProvider(MetadataProvider provider, Book book) {
        return getParser(provider).fetchTopMetadata(book, buildFetchMetadataRequestFromBook(book));
    }

    private BookParser getParser(MetadataProvider provider) {
        BookParser parser = parserMap.get(provider);
        if (parser == null) {
            throw ApiError.METADATA_SOURCE_NOT_IMPLEMENT_OR_DOES_NOT_EXIST.createException();
        }
        return parser;
    }

    private FetchMetadataRequest buildFetchMetadataRequestFromBook(Book book) {
        BookMetadata metadata = book.getMetadata();
        if (metadata == null) {
            return FetchMetadataRequest.builder()
                    .bookId(book.getId())
                    .build();
        }
        String isbn = metadata.getIsbn13();
        if (isbn == null || isbn.isBlank()) {
            isbn = metadata.getIsbn10();
        }
        return FetchMetadataRequest.builder()
                .isbn(isbn)
                .asin(metadata.getAsin())
                .author(metadata.getAuthors() != null ? String.join(", ", metadata.getAuthors()) : null)
                .title(metadata.getTitle())
                .bookId(book.getId())
                .build();
    }

    public BookMetadata buildFetchMetadata(BookMetadata existingMetadata, Long bookId, MetadataRefreshOptions refreshOptions, Map<MetadataProvider, BookMetadata> metadataMap) {
        return METADATA_MERGER.buildFetchMetadata(existingMetadata, bookId, refreshOptions, metadataMap);
    }

    protected <T > T resolveField(Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, Function < BookMetadata, T > extractor) {
        return METADATA_MERGER.resolveField(metadataMap, fieldProvider, extractor);
    }

    protected Integer resolveFieldAsInteger (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, Function < BookMetadata, Integer > fieldValueExtractor){
        return METADATA_MERGER.resolveFieldAsInteger(metadataMap, fieldProvider, fieldValueExtractor);
    }

    protected String resolveFieldAsString (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractor fieldValueExtractor){
        return METADATA_MERGER.resolveFieldAsString(metadataMap, fieldProvider, fieldValueExtractor);
    }

    protected List<String> resolveFieldAsList (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        return METADATA_MERGER.resolveFieldAsList(metadataMap, fieldProvider, fieldValueExtractor);
    }

    protected Set<String> resolveFieldAsSet (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        return METADATA_MERGER.resolveFieldAsSet(metadataMap, fieldProvider, fieldValueExtractor);
    }

    Set<String> getAllCategories (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        return METADATA_MERGER.getAllCategories(metadataMap, fieldProvider, fieldValueExtractor);
    }

    protected Set<Long> getBookEntities (MetadataRefreshRequest request){
        MetadataRefreshRequest.RefreshType refreshType = request.getRefreshType();
        if (refreshType != MetadataRefreshRequest.RefreshType.LIBRARY && refreshType != MetadataRefreshRequest.RefreshType.BOOKS) {
            throw ApiError.INVALID_REFRESH_TYPE.createException();
        }
        return switch (refreshType) {
            case LIBRARY -> {
                LibraryEntity libraryEntity = libraryRepository.findById(request.getLibraryId()).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(request.getLibraryId()));
                yield bookRepository.findBookIdsByLibraryId(libraryEntity.getId());
            }
            case BOOKS -> request.getBookIds();
        };
    }

    private Set<Shelf> filterShelvesByUserId(Set<Shelf> shelves, Long userId) {
        return BookUtils.filterShelvesByUserId(shelves, userId);
    }
}
