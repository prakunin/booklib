package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.inpx.InpxImportRequest;
import org.booklore.model.dto.inpx.InpxImportResult;
import org.booklore.model.dto.inpx.InpxArchiveDto;
import org.booklore.model.dto.inpx.InpxArchiveRescanRequest;
import org.booklore.model.dto.inpx.InpxArchiveScanTaskDto;
import org.booklore.model.dto.inpx.InpxSearchResult;
import org.booklore.config.security.annotation.CheckLibraryAccess;
import org.booklore.service.inpx.InpxArchiveCatalogService;
import org.booklore.service.inpx.InpxArchiveFullScanService;
import org.booklore.service.inpx.InpxImportService;
import org.booklore.service.inpx.InpxParser;
import org.booklore.service.library.LibraryService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inpx")
@RequiredArgsConstructor
@Tag(name = "INPX Import", description = "Browse INPX indexes and import selected FB2 books")
public class InpxController {

    private final InpxParser inpxParser;
    private final InpxImportService inpxImportService;
    private final LibraryService libraryService;
    private final InpxArchiveCatalogService archiveCatalogService;
    private final InpxArchiveFullScanService archiveFullScanService;

    @GetMapping("/books")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canUpload()")
    @Operation(summary = "Search an INPX catalog")
    public ResponseEntity<InpxSearchResult> search(
            @RequestParam String inpxPath,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(inpxParser.search(inpxPath, query, limit));
    }

    @PostMapping("/import")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canUpload()")
    @Operation(summary = "Extract selected INPX books into a library")
    public ResponseEntity<InpxImportResult> importBooks(@Valid @RequestBody InpxImportRequest request) {
        InpxImportResult result = inpxImportService.importBooks(request);
        if (result.getImported() > 0) {
            libraryService.rescanLibrary(request.getLibraryId());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/libraries/{libraryId}/archives")
    @CheckLibraryAccess(libraryIdParam = "libraryId")
    @PreAuthorize("@securityUtil.canManageLibrary() or @securityUtil.isAdmin()")
    @Operation(summary = "List ZIP archives and scan statistics for an INPX library")
    public ResponseEntity<List<InpxArchiveDto>> getArchives(@PathVariable long libraryId) {
        return ResponseEntity.ok(archiveCatalogService.list(libraryId));
    }

    @GetMapping("/libraries/{libraryId}/archive-scans")
    @CheckLibraryAccess(libraryIdParam = "libraryId")
    @PreAuthorize("@securityUtil.canManageLibrary() or @securityUtil.isAdmin()")
    @Operation(summary = "List queued, active, and completed INPX archive scans")
    public ResponseEntity<List<InpxArchiveScanTaskDto>> getArchiveScans(@PathVariable long libraryId) {
        return ResponseEntity.ok(archiveCatalogService.listTasks(libraryId));
    }

    @PostMapping("/libraries/{libraryId}/archives/rescan")
    @CheckLibraryAccess(libraryIdParam = "libraryId")
    @PreAuthorize("@securityUtil.canManageLibrary() or @securityUtil.isAdmin()")
    @Operation(summary = "Start a full metadata and cover rescan of one INPX ZIP archive")
    public ResponseEntity<Void> rescanArchive(@PathVariable long libraryId,
                                              @Valid @RequestBody InpxArchiveRescanRequest request) {
        archiveFullScanService.start(libraryId, request.getArchiveName());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
