package org.booklore.app.controller;

import org.booklore.app.dto.AppFilterOptions;
import org.booklore.app.dto.AppLibraryStats;
import org.booklore.app.service.AppBookService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppStatsControllerTest {

    private final AppBookService appBookService = mock(AppBookService.class);
    private final AppStatsController controller = new AppStatsController(appBookService);

    @Test
    void returnsCompactLibraryStatsForRequestedScope() {
        AppLibraryStats stats = new AppLibraryStats(
                42, 2048, 12, 5, 3, 18,
                AppFilterOptions.builder().build(),
                List.of(new AppLibraryStats.MonthlyCount(2026, 7, 4)),
                List.of(new AppLibraryStats.MonthlyCount(2026, 7, 2)),
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(appBookService.getLibraryStats(7L)).thenReturn(stats);

        ResponseEntity<AppLibraryStats> response = controller.getLibraryStats(7L);

        assertThat(response.getBody()).isSameAs(stats);
        verify(appBookService).getLibraryStats(7L);
    }
}
