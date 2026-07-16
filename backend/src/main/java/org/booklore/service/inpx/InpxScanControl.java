package org.booklore.service.inpx;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InpxScanControl {

    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();

    public void requestCancel(long libraryId) {
        cancelRequested.add(libraryId);
    }

    public boolean isCancelRequested(long libraryId) {
        return cancelRequested.contains(libraryId);
    }

    public void clear(long libraryId) {
        cancelRequested.remove(libraryId);
    }
}
