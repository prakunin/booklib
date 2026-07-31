package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.booklore.config.security.annotation.CheckBookAccess;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.response.DjvuRenditionStatus;
import org.booklore.service.djvu.DjvuBookLocator;
import org.booklore.service.djvu.DjvuRenditionService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/djvu")
@RequiredArgsConstructor
@Tag(name = "DjVu Rendition", description = "The searchable PDF rendition built from a DjVu book")
public class DjvuRenditionController {

    private final DjvuBookLocator bookLocator;
    private final DjvuRenditionService renditionService;

    /**
     * Whether this book can be opened in the PDF reader yet.
     * <p>
     * Asking also queues the rendition when there is none, so the first person to open a DjVu book
     * is what starts it being built - the reader they get in the meantime is the page reader, which
     * needs nothing.
     */
    @Operation(summary = "Rendition status", description = "Whether the searchable PDF rendition of a DjVu book is ready.")
    @ApiResponse(responseCode = "200", description = "Status returned successfully")
    @GetMapping("/{bookId}/rendition-status")
    @CheckBookAccess(bookIdParam = "bookId")
    public DjvuRenditionStatus renditionStatus(
            @Parameter(description = "ID of the book") @PathVariable Long bookId,
            @Parameter(description = "Optional book type for alternative format") @RequestParam(required = false) String bookType) {
        Path source = bookLocator.locate(bookId, bookType);
        boolean ready = renditionService.hasRendition(bookId, source);
        if (!ready) {
            renditionService.requestRendition(bookId, source);
        }
        return new DjvuRenditionStatus(ready);
    }

    @Operation(summary = "Get the PDF rendition", description = "Serves the searchable PDF built from a DjVu book.")
    @ApiResponse(responseCode = "200", description = "Rendition returned successfully")
    @ApiResponse(responseCode = "404", description = "No rendition has been built for this book")
    @GetMapping(value = "/{bookId}/rendition", produces = MediaType.APPLICATION_PDF_VALUE)
    @CheckBookAccess(bookIdParam = "bookId")
    public ResponseEntity<Resource> rendition(
            @Parameter(description = "ID of the book") @PathVariable Long bookId,
            @Parameter(description = "Optional book type for alternative format") @RequestParam(required = false) String bookType) {
        Path source = bookLocator.locate(bookId, bookType);
        Path rendition = renditionService.renditionPath(bookId, source)
                .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException(
                        "No PDF rendition has been built for book " + bookId));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new FileSystemResource(rendition));
    }
}
