package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.config.security.annotation.CheckBookAccess;
import org.booklore.model.dto.smart.SmartEnrichmentEvent;
import org.booklore.service.metadata.smart.SmartEnrichmentService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/books")
@AllArgsConstructor
@Tag(name = "Smart Enrichment", description = "Agent-assisted metadata enrichment for a single book")
public class SmartEnrichmentController {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final SmartEnrichmentService smartEnrichmentService;

    /**
     * Lets the UI hide the action entirely rather than offering a button that can only fail: the
     * agent binary is operator-supplied, so most instances will not have it.
     */
    @Operation(summary = "Whether smart enrichment is available on this instance")
    @ApiResponse(responseCode = "200", description = "Availability returned successfully")
    @GetMapping("/metadata/smart-enrich/availability")
    @PreAuthorize("@securityUtil.canEditMetadata() or @securityUtil.isAdmin()")
    public SmartEnrichmentAvailability availability() {
        return new SmartEnrichmentAvailability(smartEnrichmentService.isAvailable());
    }

    public record SmartEnrichmentAvailability(boolean enabled) {
    }

    /**
     * Streamed rather than returned, because identification runs for tens of seconds: the client
     * needs to show what is happening instead of holding an idle request open.
     */
    @Operation(summary = "Suggest metadata for a book using an agent",
            description = "Identifies the underlying work, verifies its rating against Goodreads, and streams the resulting proposals. Nothing is written. Requires metadata edit permission or admin.")
    @ApiResponse(responseCode = "200", description = "Enrichment events streamed successfully")
    @PostMapping(value = "/{bookId}/metadata/smart-enrich", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@securityUtil.canEditMetadata() or @securityUtil.isAdmin()")
    @CheckBookAccess(bookIdParam = "bookId")
    public Flux<ServerSentEvent<SmartEnrichmentEvent>> enrich(
            @Parameter(description = "ID of the book") @PathVariable Long bookId) {
        Flux<ServerSentEvent<SmartEnrichmentEvent>> events = smartEnrichmentService.enrich(bookId)
                .map(event -> ServerSentEvent.builder(event).build());
        // The agent runs for a minute or more with nothing to report between stages. A bare idle
        // SSE connection gets dropped by the browser or an intermediate NAT/firewall long before the
        // agent finishes, which the client can only report as a generic "network error". Comment-only
        // frames every few seconds keep the connection alive; the frontend parser ignores non-data
        // lines. takeUntil ends the merged stream (and cancels the heartbeat) once a terminal event
        // has passed through, so the interval does not keep the request open forever.
        Flux<ServerSentEvent<SmartEnrichmentEvent>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<SmartEnrichmentEvent>builder().comment("keepalive").build());
        return events.mergeWith(heartbeat)
                .takeUntil(sse -> {
                    SmartEnrichmentEvent data = sse.data();
                    return data != null && (data.stage() == SmartEnrichmentEvent.Stage.COMPLETED
                            || data.stage() == SmartEnrichmentEvent.Stage.FAILED);
                });
    }
}
