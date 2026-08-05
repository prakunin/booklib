package org.booklore.service.inpx;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-scan lookup caches. Holds ids, not entities: entities cached across the
 * per-batch transactions would be detached.
 */
public class InpxScanCaches {

    private static final int MAX_ENTRIES = 100_000;

    /**
     * How many newly persisted books one scan may hand to {@code enrichment_queue}.
     * <p>
     * The queue is sized for rate-limited provider calls: {@code EnrichmentWorker} drains five books
     * every fifteen seconds, i.e. 1,200 an hour. That is the right shape for a rescan of an already
     * imported library, which is what "a scan adds a handful at a time" describes — it brings retries,
     * deduplication and priority for a few hundred books at no real cost. It is the wrong shape for a
     * first import: the 702,511-book library this feature exists for would queue about 24 days of work,
     * which is the identical arithmetic {@code LocalCatalogBackfillService}'s own javadoc uses to
     * explain why the backfill does not use the queue at all.
     * <p>
     * So the queue keeps the case it was justified for and stops at this budget. Bulk is the backfill
     * task's job: it is per-library, walks the archive cursor directly, and is launched from the same
     * INPX archive panel the import is. Under {@code AUTO_IF_EMPTY} the books this budget did queue are
     * no-ops when the backfill later reaches them, so the two do not fight.
     * <p>
     * The counter is per scan rather than per batch on purpose: a per-batch cap would let a 1,400-batch
     * import through unchanged.
     */
    private static final int MAX_ENRICHMENT_QUEUE_BOOKS = 5_000;

    private final Map<String, Long> authors = boundedMap();
    private final Map<String, Long> categories = boundedMap();

    private int queuedForEnrichment;

    public Map<String, Long> authors() {
        return authors;
    }

    public Map<String, Long> categories() {
        return categories;
    }

    /**
     * Takes up to {@code requested} slots from this scan's enrichment-queue budget.
     *
     * @return how many books the caller may queue — {@code requested} until the budget runs out, then
     * less, then zero. Never negative.
     */
    public int claimEnrichmentQueueSlots(int requested) {
        if (requested <= 0 || queuedForEnrichment >= MAX_ENRICHMENT_QUEUE_BOOKS) {
            return 0;
        }
        int granted = Math.min(requested, MAX_ENRICHMENT_QUEUE_BOOKS - queuedForEnrichment);
        queuedForEnrichment += granted;
        return granted;
    }

    public boolean isEnrichmentQueueBudgetExhausted() {
        return queuedForEnrichment >= MAX_ENRICHMENT_QUEUE_BOOKS;
    }

    public static int maxEnrichmentQueueBooks() {
        return MAX_ENRICHMENT_QUEUE_BOOKS;
    }

    private Map<String, Long> boundedMap() {
        return new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
    }
}
