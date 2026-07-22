package org.booklore.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.booklore.app.dto.AppAuthorDetail;
import org.booklore.app.dto.AppAuthorSummary;
import org.booklore.app.dto.AppPageResponse;
import org.booklore.app.service.AppAuthorService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/app/authors")
@Tag(name = "App Authors", description = "Endpoints for browsing authors in the app experience")
public class AppAuthorController {

    private final AppAuthorService mobileAuthorService;

    @Operation(
            summary = "List app authors",
            description = "Retrieve paginated authors for the app with optional filtering and sorting.",
            operationId = "appGetAuthors"
    )
    @GetMapping
    public ResponseEntity<AppPageResponse<AppAuthorSummary>> getAuthors(
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "30") Integer size,
            @RequestParam(required = false, defaultValue = "name") String sort,
            @RequestParam(required = false, defaultValue = "asc") String dir,
            @RequestParam(required = false) Long libraryId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean hasPhoto,
            @RequestParam(required = false) String matchStatus,
            @RequestParam(required = false) String readStatus,
            @RequestParam(required = false) String bookCount,
            @RequestParam(required = false) String genre) {

        return ResponseEntity.ok(mobileAuthorService.getAuthors(
                page, size, sort, dir, libraryId, search, hasPhoto,
                matchStatus, readStatus, bookCount, genre));
    }

    @Operation(
            summary = "List app author categories",
            description = "Retrieve categories available to the current user for author browsing.",
            operationId = "appGetAuthorCategories"
    )
    @GetMapping("/categories")
    public ResponseEntity<List<String>> getAuthorCategories(
            @RequestParam(required = false) Long libraryId) {
        return ResponseEntity.ok(mobileAuthorService.getCategories(libraryId));
    }

    @Operation(
            summary = "Get app author details",
            description = "Retrieve detailed app-facing information for a single author.",
            operationId = "appGetAuthorDetail"
    )
    @GetMapping("/{authorId}")
    public ResponseEntity<AppAuthorDetail> getAuthorDetail(
            @PathVariable Long authorId) {

        return ResponseEntity.ok(mobileAuthorService.getAuthorDetail(authorId));
    }
}
