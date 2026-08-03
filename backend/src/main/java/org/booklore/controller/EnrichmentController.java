package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.booklore.model.dto.request.EnrichmentRequest;
import org.booklore.model.enums.EnrichmentQueueStatus;
import org.booklore.repository.EnrichmentQueueRepository;
import org.booklore.service.enrichment.catalog.LocalCatalogDetector;
import org.booklore.service.enrichment.catalog.LocalCatalogIndexService;
import org.booklore.service.enrichment.queue.EnrichmentPriority;
import org.booklore.service.enrichment.queue.EnrichmentQueueService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.EnumMap;
import java.util.Map;

/**
 * The single entry point for enrichment.
 * <p>
 * Everything a caller can ask for — one book, a selection, a whole library — is the same request
 * with a different scope, so the ordering, cost control and write policy are the pipeline's to
 * decide rather than each caller's.
 */
@RestController
@RequestMapping("/api/v1/enrichment")
@AllArgsConstructor
@Tag(name = "Enrichment", description = "Unified metadata enrichment: local catalog, providers and agent")
public class EnrichmentController {

    private final EnrichmentQueueService queueService;
    private final EnrichmentQueueRepository queueRepository;
    private final LocalCatalogIndexService localCatalogIndexService;
    private final LocalCatalogDetector localCatalogDetector;

    /**
     * Accepted rather than executed: even a single book can involve provider calls measured in
     * seconds and an agent call measured in minutes, so the request returns a job to follow instead
     * of holding the connection open.
     */
    @Operation(summary = "Enrich books",
            description = "Queues enrichment for one book, a selection or a whole library. Requires metadata edit permission or admin.")
    @ApiResponse(responseCode = "202", description = "Enrichment queued")
    @PostMapping
    @PreAuthorize("@securityUtil.canEditMetadata() or @securityUtil.isAdmin()")
    public ResponseEntity<EnqueueResponse> enrich(@Valid @RequestBody EnrichmentRequest request) {
        String jobId = queueService.enqueue(request, priorityFor(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new EnqueueResponse(jobId));
    }

    /**
     * A user watching one book should not wait behind a library sweep queued an hour ago.
     */
    private int priorityFor(EnrichmentRequest request) {
        return switch (request.getScope()) {
            case BOOK -> EnrichmentPriority.SINGLE_BOOK;
            case BOOKS -> EnrichmentPriority.SELECTION;
            case LIBRARY -> EnrichmentPriority.LIBRARY_SWEEP;
        };
    }

    @Operation(summary = "Progress of an enrichment job")
    @ApiResponse(responseCode = "200", description = "Progress returned successfully")
    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("@securityUtil.canEditMetadata() or @securityUtil.isAdmin()")
    public EnrichmentQueueService.JobProgress progress(
            @Parameter(description = "Job id returned when the work was queued") @PathVariable String jobId) {
        return queueService.progress(jobId);
    }

    /**
     * Cancels what has not started. A book already being enriched runs to completion — abandoning it
     * mid-write would be worse than the few seconds it takes to finish.
     */
    @Operation(summary = "Cancel the queued part of an enrichment job")
    @ApiResponse(responseCode = "200", description = "Cancellation applied")
    @PostMapping("/jobs/{jobId}/cancel")
    @PreAuthorize("@securityUtil.canEditMetadata() or @securityUtil.isAdmin()")
    public CancelResponse cancel(@PathVariable String jobId) {
        return new CancelResponse(queueService.cancel(jobId));
    }

    @Operation(summary = "How much enrichment work is outstanding")
    @ApiResponse(responseCode = "200", description = "Queue overview returned successfully")
    @GetMapping("/queue")
    @PreAuthorize("@securityUtil.isAdmin()")
    public Map<EnrichmentQueueStatus, Long> queue() {
        Map<EnrichmentQueueStatus, Long> counts = new EnumMap<>(EnrichmentQueueStatus.class);
        for (EnrichmentQueueStatus status : EnrichmentQueueStatus.values()) {
            counts.put(status, queueRepository.countByStatus(status));
        }
        return counts;
    }

    /**
     * Suggests, never applies. Pointing a library at a catalog is the user's decision, and a wrong
     * guess applied silently would stay invisible until descriptions started arriving from the wrong
     * source.
     */
    @Operation(summary = "Look for a local metadata catalog next to a library's archives")
    @ApiResponse(responseCode = "200", description = "Detection result returned successfully")
    @GetMapping("/local-catalog/detect")
    @PreAuthorize("@securityUtil.isAdmin()")
    public DetectResponse detectLocalCatalog(
            @Parameter(description = "Directory holding the library's archives") @RequestParam String archivePath) {
        return new DetectResponse(localCatalogDetector.detect(archivePath).map(java.nio.file.Path::toString).orElse(null));
    }

    /**
     * Walks 300-odd containers and writes hundreds of thousands of rows, so it runs in the
     * background and the request only reports whether it started.
     */
    @Operation(summary = "Rebuild a library's local catalog index")
    @ApiResponse(responseCode = "202", description = "Indexing started or already running")
    @PostMapping("/local-catalog/{libraryId}/reindex")
    @PreAuthorize("@securityUtil.isAdmin()")
    public ResponseEntity<ReindexResponse> reindexLocalCatalog(@PathVariable long libraryId) {
        boolean started = localCatalogIndexService.rebuildAsync(libraryId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ReindexResponse(started));
    }

    public record EnqueueResponse(String jobId) {
    }

    public record CancelResponse(int cancelled) {
    }

    public record DetectResponse(String path) {
    }

    public record ReindexResponse(boolean started) {
    }
}
