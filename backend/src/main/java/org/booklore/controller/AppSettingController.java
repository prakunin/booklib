package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.model.dto.settings.AppSettingKey;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.OidcProviderDetails;
import org.booklore.model.dto.settings.SettingRequest;
import org.booklore.model.dto.smart.AgentCliStatus;
import org.booklore.model.dto.smart.AgentCliTestResult;
import org.booklore.model.enums.AuditAction;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.metadata.smart.AgentCliStatusService;
import org.booklore.service.oidc.OidcDiagnosticService;
import org.booklore.service.recommender.OllamaEmbeddingClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;

import java.util.List;

@Tag(name = "App Settings", description = "Endpoints for retrieving and updating application settings")
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/settings")
public class AppSettingController {

    private final AppSettingService appSettingService;
    private final OidcDiagnosticService oidcDiagnosticService;
    private final AuditService auditService;
    private final OllamaEmbeddingClient ollamaEmbeddingClient;
    private final AgentCliStatusService agentCliStatusService;

    @Operation(summary = "Get application settings", description = "Retrieve all application settings.")
    @ApiResponse(responseCode = "200", description = "Application settings returned successfully")
    @GetMapping
    public AppSettings getAppSettings() {
        return appSettingService.getAppSettings();
    }

    @Operation(summary = "Update application settings", description = "Update one or more application settings.")
    @ApiResponse(responseCode = "200", description = "Settings updated successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canManageGlobalPreferences()")
    @PutMapping
    public void updateSettings(@Parameter(description = "List of settings to update") @RequestBody List<SettingRequest> settingRequests) throws JacksonException {
        for (SettingRequest settingRequest : settingRequests) {
            AppSettingKey key = AppSettingKey.valueOf(settingRequest.getName());
            appSettingService.updateSetting(key, settingRequest.getValue());
        }
    }

    @PostMapping("/oidc/test")
    @PreAuthorize("@securityUtil.isAdmin()")
    public OidcDiagnosticService.OidcTestResult testOidcConnection(@RequestBody OidcProviderDetails providerDetails) {
        var result = oidcDiagnosticService.testConnection(providerDetails);
        auditService.log(AuditAction.OIDC_CONNECTION_TEST, "OIDC connection test: " + (result.success() ? "passed" : "failed"));
        return result;
    }

    @GetMapping("/recommendation-embedding/models")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canManageGlobalPreferences()")
    public List<String> getRecommendationEmbeddingModels() {
        return ollamaEmbeddingClient.listModels();
    }

    @Operation(summary = "Agent CLI status", description = "Whether the enrichment agent is installed and signed in, and which models it offers.")
    @GetMapping("/smart-enrichment/status")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canManageGlobalPreferences()")
    public AgentCliStatus getSmartEnrichmentStatus() {
        return agentCliStatusService.status();
    }

    @Operation(summary = "Test the agent CLI", description = "Runs a minimal real prompt to prove the binary, credentials and network all work.")
    @PostMapping("/smart-enrichment/test")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canManageGlobalPreferences()")
    public AgentCliTestResult testSmartEnrichment() {
        return agentCliStatusService.test();
    }
}
