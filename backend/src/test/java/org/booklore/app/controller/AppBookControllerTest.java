package org.booklore.app.controller;

import org.booklore.app.service.AppBookService;
import org.booklore.app.dto.AppCatalogSummary;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppBookControllerTest {

    private final AppBookService appBookService = mock(AppBookService.class);
    private final AppBookController controller = new AppBookController(appBookService);

    @Test
    void shouldReturnExistingIsbnMatches() {
        Set<String> requested = Set.of("9781234567890", "123456789X");
        Set<String> matches = Set.of("9781234567890");
        when(appBookService.findExistingIsbns(7L, requested)).thenReturn(matches);

        var response = controller.findExistingIsbns(7L, requested);

        assertThat(response.getBody()).isEqualTo(matches);
        verify(appBookService).findExistingIsbns(7L, requested);
    }

    @Test
    void shouldReturnCatalogSummary() {
        AppCatalogSummary summary = new AppCatalogSummary(12, 8, 3, 2, java.util.Map.of(1L, 12L));
        when(appBookService.getCatalogSummary()).thenReturn(summary);

        var response = controller.getCatalogSummary();

        assertThat(response.getBody()).isEqualTo(summary);
        verify(appBookService).getCatalogSummary();
    }
}
