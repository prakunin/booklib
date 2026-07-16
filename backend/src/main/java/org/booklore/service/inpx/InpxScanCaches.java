package org.booklore.service.inpx;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-scan lookup caches. Holds ids, not entities: entities cached across the
 * per-batch transactions would be detached.
 */
public class InpxScanCaches {

    private static final int MAX_ENTRIES = 100_000;

    private final Map<String, Long> authors = boundedMap();
    private final Map<String, Long> categories = boundedMap();

    public Map<String, Long> authors() {
        return authors;
    }

    public Map<String, Long> categories() {
        return categories;
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
