package org.booklore.controller;

import org.booklore.exception.APIException;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.inpx.LocalCatalogStatusDto;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.LocalCatalogSourceType;
import org.booklore.service.enrichment.catalog.LocalCatalogStatusService;
import org.booklore.service.inpx.InpxArchiveCatalogService;
import org.booklore.service.inpx.InpxArchiveFullScanService;
import org.booklore.service.inpx.InpxImportService;
import org.booklore.service.inpx.InpxParser;
import org.booklore.service.inpx.InpxSourceResolver;
import org.booklore.service.library.LibraryService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InpxControllerTest {

    private final InpxParser inpxParser = mock(InpxParser.class);
    private final InpxImportService inpxImportService = mock(InpxImportService.class);
    private final LibraryService libraryService = mock(LibraryService.class);
    private final InpxArchiveCatalogService archiveCatalogService = mock(InpxArchiveCatalogService.class);
    private final InpxArchiveFullScanService archiveFullScanService = mock(InpxArchiveFullScanService.class);
    private final InpxSourceResolver inpxSourceResolver = mock(InpxSourceResolver.class);
    private final LocalCatalogStatusService localCatalogStatusService = mock(LocalCatalogStatusService.class);

    private final InpxController controller = new InpxController(
            inpxParser, inpxImportService, libraryService, archiveCatalogService,
            archiveFullScanService, inpxSourceResolver, localCatalogStatusService);

    @Test
    void startsAFullScanForEveryIdleArchiveInTheLibrary() {
        var response = controller.rescanAllArchives(19L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(archiveFullScanService).startAll(19L);
    }

    @Nested
    class GetLocalCatalogStatus {

        @Test
        void resolvesTheInpxLibraryThenDelegatesToTheStatusService() {
            LibraryEntity library = LibraryEntity.builder().id(19L)
                    .metadataSidecarPath("/data/catalog/fb2.Flibusta.Net.FLibrary.etc").build();
            LocalCatalogStatusDto dto = new LocalCatalogStatusDto(
                    true, "/data/catalog/fb2.Flibusta.Net.FLibrary.etc",
                    Map.of(LocalCatalogSourceType.REVIEW, 1L), 2L, 3L, 4L, 5L);
            when(archiveCatalogService.requireInpxLibrary(19L)).thenReturn(library);
            when(localCatalogStatusService.getStatus(library)).thenReturn(dto);

            var response = controller.getLocalCatalogStatus(19L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isSameAs(dto);
            verify(archiveCatalogService).requireInpxLibrary(19L);
            verify(localCatalogStatusService).getStatus(library);
        }

        @Test
        void doesNotCatchTheGateExceptionForANonInpxOrMissingLibrary() {
            when(archiveCatalogService.requireInpxLibrary(19L))
                    .thenThrow(ApiError.GENERIC_BAD_REQUEST.createException(
                            "Archive management is available only for INPX libraries"));

            assertThatExceptionOfType(APIException.class)
                    .isThrownBy(() -> controller.getLocalCatalogStatus(19L))
                    .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(ApiError.GENERIC_BAD_REQUEST.getStatus()));
        }
    }
}
