package org.booklore.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.booklore.app.dto.AppLibrarySummary;
import org.booklore.app.service.AppLibraryService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/app/libraries")
@Tag(name = "App Libraries", description = "Endpoints for retrieving libraries in the app experience")
public class AppLibraryController {

    private final AppLibraryService appLibraryService;

    @Operation(
            summary = "List app libraries",
            description = "Retrieve libraries visible to the current app user, including per-library book counts.",
            operationId = "appGetLibraries"
    )
    @GetMapping
    public ResponseEntity<List<AppLibrarySummary>> getLibraries() {
        return ResponseEntity.ok(appLibraryService.getLibraries());
    }
}
