package org.booklore.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.app.dto.AppLibraryStats;
import org.booklore.app.service.AppBookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/app/stats")
@Tag(name = "App Statistics", description = "Server-aggregated statistics for large catalogs")
public class AppStatsController {

    private final AppBookService appBookService;

    @Operation(summary = "Get library statistics", description = "Returns compact chart aggregates without transferring the catalog")
    @GetMapping("/library")
    public ResponseEntity<AppLibraryStats> getLibraryStats(@RequestParam(required = false) Long libraryId) {
        return ResponseEntity.ok(appBookService.getLibraryStats(libraryId));
    }
}
