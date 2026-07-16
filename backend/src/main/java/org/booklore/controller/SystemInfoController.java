package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.model.dto.system.SystemInfoDto;
import org.booklore.service.system.SystemInfoService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/system-info")
@Tag(name = "System Info", description = "Endpoints for inspecting the runtime environment")
public class SystemInfoController {

    private final SystemInfoService systemInfoService;

    @Operation(summary = "Get system information",
            description = "Versions, runtime, OS, storage and tool information. Requires admin.")
    @ApiResponse(responseCode = "200", description = "System information returned successfully")
    @GetMapping
    @PreAuthorize("@securityUtil.isAdmin()")
    public ResponseEntity<SystemInfoDto> getSystemInfo() {
        return ResponseEntity.ok(systemInfoService.getSystemInfo());
    }
}
