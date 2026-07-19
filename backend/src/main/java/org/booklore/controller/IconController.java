package org.booklore.controller;

import org.booklore.model.dto.request.SvgIconBatchRequest;
import org.booklore.model.dto.request.SvgIconCreateRequest;
import org.booklore.model.dto.response.SvgIconBatchResponse;
import org.booklore.service.IconService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

@Tag(name = "Icons", description = "Endpoints for managing SVG icons")
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/icons")
public class IconController {

    private static final CacheControl SVG_CACHE = CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate();
    private static final MediaType SVG_MEDIA_TYPE = MediaType.valueOf("image/svg+xml");

    private final IconService iconService;

    @Operation(summary = "Save an SVG icon", description = "Saves an SVG icon to the system.")
    @ApiResponse(responseCode = "200", description = "SVG icon saved successfully")
    @PostMapping
    @PreAuthorize("@securityUtil.canManageIcons() or @securityUtil.isAdmin()")
    public ResponseEntity<Void> saveSvgIcon(@Valid @RequestBody SvgIconCreateRequest svgIconCreateRequest) {
        iconService.saveSvgIcon(svgIconCreateRequest);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Save multiple SVG icons", description = "Saves multiple SVG icons to the system in batch.")
    @ApiResponse(responseCode = "200", description = "Batch save completed with detailed results")
    @PostMapping("/batch")
    @PreAuthorize("@securityUtil.canManageIcons() or @securityUtil.isAdmin()")
    public ResponseEntity<SvgIconBatchResponse> saveBatchSvgIcons(@Valid @RequestBody SvgIconBatchRequest request) {
        SvgIconBatchResponse response = iconService.saveBatchSvgIcons(request.getIcons());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get SVG icon content", description = "Retrieve the SVG content of an icon by its name.")
    @ApiResponse(responseCode = "200", description = "SVG icon content retrieved successfully")
    @GetMapping("/{svgName}/content")
    public ResponseEntity<String> getSvgIconContent(
            @Parameter(description = "SVG icon name") @PathVariable String svgName,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        String svgContent = iconService.getSvgIcon(svgName);
        String etag = quotedSha256(svgContent);
        if (matchesIfNoneMatch(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(SVG_CACHE)
                    .eTag(etag)
                    .build();
        }
        return ResponseEntity.ok()
                .cacheControl(SVG_CACHE)
                .eTag(etag)
                .contentType(SVG_MEDIA_TYPE)
                .body(svgContent);
    }

    @Operation(summary = "Get paginated icon names", description = "Retrieve a paginated list of icon names (default 50 per page).")
    @ApiResponse(responseCode = "200", description = "Icon names retrieved successfully")
    @GetMapping
    public ResponseEntity<Page<String>> getIconNames(
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size) {
        Page<String> response = iconService.getIconNames(page, size);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Delete an SVG icon", description = "Deletes an SVG icon by its name.")
    @ApiResponse(responseCode = "200", description = "SVG icon deleted successfully")
    @DeleteMapping("/{svgName}")
    @PreAuthorize("@securityUtil.canManageIcons() or @securityUtil.isAdmin()")
    public ResponseEntity<Void> deleteSvgIcon(@Parameter(description = "SVG icon name") @PathVariable String svgName) {
        iconService.deleteSvgIcon(svgName);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Get all icon contents", description = "Retrieve all SVG icons as a map of icon names to their content.")
    @ApiResponse(responseCode = "200", description = "All icon contents retrieved successfully")
    @GetMapping("/all/content")
    public ResponseEntity<Map<String, String>> getAllIconsContent(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Map<String, String> iconsMap = iconService.getAllIconsContent();
        String etag = quotedSha256(iconsMap);
        if (matchesIfNoneMatch(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(SVG_CACHE)
                    .eTag(etag)
                    .build();
        }
        return ResponseEntity.ok()
                .cacheControl(SVG_CACHE)
                .eTag(etag)
                .body(iconsMap);
    }

    private String quotedSha256(String content) {
        MessageDigest digest = sha256Digest();
        digest.update(content.getBytes(StandardCharsets.UTF_8));
        return quote(digest.digest());
    }

    private String quotedSha256(Map<String, String> iconsMap) {
        MessageDigest digest = sha256Digest();
        new TreeMap<>(iconsMap).forEach((name, content) -> {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(content.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        });
        return quote(digest.digest());
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }

    private String quote(byte[] digest) {
        return "\"" + HexFormat.of().formatHex(digest) + "\"";
    }

    private boolean matchesIfNoneMatch(String ifNoneMatch, String currentEtag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        if ("*".equals(ifNoneMatch.trim())) {
            return true;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            if (weakEtagValue(candidate.trim()).equals(weakEtagValue(currentEtag))) {
                return true;
            }
        }
        return false;
    }

    private String weakEtagValue(String etag) {
        return etag.startsWith("W/") ? etag.substring(2) : etag;
    }
}
