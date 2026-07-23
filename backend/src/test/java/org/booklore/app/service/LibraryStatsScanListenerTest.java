package org.booklore.app.service;

import org.booklore.service.event.LibraryScanCompletedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LibraryStatsScanListenerTest {

    @Test
    void recomputesTheScannedLibraryOnScanCompletion() {
        LibraryStatsRecomputeCoordinator coordinator = mock(LibraryStatsRecomputeCoordinator.class);
        LibraryStatsScanListener listener = new LibraryStatsScanListener(coordinator);

        listener.handle(new LibraryScanCompletedEvent(42L));

        verify(coordinator).recomputeAfterChange(42L);
    }
}
