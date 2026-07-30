package org.booklore.service.appsettings;

import org.booklore.config.AppProperties;
import org.booklore.config.RecommendationEmbeddingProperties;
import org.booklore.config.SmartEnrichmentProperties;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.settings.AppSettingKey;
import org.booklore.model.dto.settings.PasswordPolicy;
import org.booklore.model.entity.AppSettingEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.repository.AppSettingsRepository;
import org.booklore.service.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSettingServiceTest {

    @Mock
    private AppProperties appProperties;
    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private AuditService auditService;
    @Mock
    private AppSettingsRepository appSettingsRepository;

    private SettingPersistenceHelper settingPersistenceHelper;

    private AppSettingService appSettingService;
    private RecommendationEmbeddingProperties recommendationEmbeddingProperties;
    private SmartEnrichmentProperties smartEnrichmentProperties;

    @BeforeEach
    void setUp() {
        settingPersistenceHelper = new SettingPersistenceHelper(appSettingsRepository, new ObjectMapper());
        recommendationEmbeddingProperties = new RecommendationEmbeddingProperties();
        smartEnrichmentProperties = new SmartEnrichmentProperties();
        appSettingService = new AppSettingService(
                appProperties,
                recommendationEmbeddingProperties,
                smartEnrichmentProperties,
                settingPersistenceHelper,
                authenticationService,
                auditService);

        var permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(true);
        BookLoreUser user = BookLoreUser.builder()
                .id(1L)
                .username("admin")
                .permissions(permissions)
                .build();

        // Lenient: reading settings needs no authenticated user, only the update paths do.
        lenient().when(authenticationService.getAuthenticatedUser()).thenReturn(user);
    }

    @Test
    void updateSetting_acceptsValidOidcRedirectUris() throws Exception {
        appSettingService.updateSetting(
                AppSettingKey.OIDC_REDIRECT_URIS,
                List.of("booklib://oauth2-callback", "booklib://auth/return")
        );

        ArgumentCaptor<AppSettingEntity> settingCaptor = ArgumentCaptor.forClass(AppSettingEntity.class);
        verify(appSettingsRepository).save(settingCaptor.capture());

        AppSettingEntity savedSetting = settingCaptor.getValue();
        assertThat(savedSetting.getName()).isEqualTo(AppSettingKey.OIDC_REDIRECT_URIS.toString());
        assertThat(savedSetting.getVal()).isEqualTo("[\"booklib://oauth2-callback\",\"booklib://auth/return\"]");
        verify(auditService).log(AuditAction.OIDC_CONFIG_CHANGED, "Updated setting: " + AppSettingKey.OIDC_REDIRECT_URIS);
    }

    @Test
    void updateSetting_rejectsWildcardOidcRedirectUri() {
        assertThatThrownBy(() -> appSettingService.updateSetting(
                AppSettingKey.OIDC_REDIRECT_URIS,
                List.of("*")
        ))
                .hasMessageContaining("Redirect URI must include a scheme");

        verify(appSettingsRepository, never()).save(any());
    }

    @Test
    void updateSetting_acceptsSingleCharacterPasswordPolicy() throws Exception {
        appSettingService.updateSetting(AppSettingKey.PASSWORD_POLICY, Map.of(
                "minimumLength", 1,
                "requireUppercase", false,
                "requireLowercase", false,
                "requireDigit", false,
                "requireSpecialCharacter", false
        ));

        ArgumentCaptor<AppSettingEntity> settingCaptor = ArgumentCaptor.forClass(AppSettingEntity.class);
        verify(appSettingsRepository).save(settingCaptor.capture());
        assertThat(settingCaptor.getValue().getName()).isEqualTo(AppSettingKey.PASSWORD_POLICY.toString());
        assertThat(settingCaptor.getValue().getVal()).contains("\"minimumLength\":1");
    }

    @Test
    void updateSetting_rejectsPasswordMinimumOutsideSupportedRange() {
        PasswordPolicy policy = PasswordPolicy.builder().minimumLength(0).build();

        assertThatThrownBy(() -> appSettingService.updateSetting(AppSettingKey.PASSWORD_POLICY, policy))
                .hasMessageContaining("between 1 and 72");

        verify(appSettingsRepository, never()).save(any());
    }

    @Test
    void updateSetting_rejectsWildcardCombinedWithOtherUris() {
        assertThatThrownBy(() -> appSettingService.updateSetting(
                AppSettingKey.OIDC_REDIRECT_URIS,
                List.of("*", "booklib://oauth2-callback")
        ))
                .hasMessageContaining("Redirect URI must include a scheme");

        verify(appSettingsRepository, never()).save(any());
    }

    @Test
    void updateSetting_rejectsBlankOidcMobileRedirectUri() {
        assertThatThrownBy(() -> appSettingService.updateSetting(
                AppSettingKey.OIDC_REDIRECT_URIS,
                List.of(" ")
        ))
                .hasMessageContaining("Redirect URI cannot be blank");

        verify(appSettingsRepository, never()).save(any());
    }

    @Test
    void updateSetting_rejectsNonStringOidcMobileRedirectUriEntries() {
        assertThatThrownBy(() -> appSettingService.updateSetting(
                AppSettingKey.OIDC_REDIRECT_URIS,
                List.of(42)
        ))
                .hasMessageContaining("OIDC redirect URIs must be an array of strings");

        verify(appSettingsRepository, never()).save(any());
    }

    @Test
    void updateSetting_rejectsDuplicateOidcMobileRedirectUris() {
        assertThatThrownBy(() -> appSettingService.updateSetting(
                AppSettingKey.OIDC_REDIRECT_URIS,
                List.of("booklib://oauth2-callback", "booklib://oauth2-callback")
        ))
                .hasMessageContaining("Duplicate redirect URI");

        verify(appSettingsRepository, never()).save(any());
    }

    @Test
    void updateSetting_rejectsHttpRedirectUri() {
        assertThatThrownBy(() -> appSettingService.updateSetting(
                AppSettingKey.OIDC_REDIRECT_URIS,
                List.of("https://example.com/oauth2-callback")
        ))
                .hasMessageContaining("Redirect URI must use a custom mobile scheme");

        verify(appSettingsRepository, never()).save(any());
    }

    @Test
    void updateSetting_rejectsRedirectUriWithFragment() {
        assertThatThrownBy(() -> appSettingService.updateSetting(
                AppSettingKey.OIDC_REDIRECT_URIS,
                List.of("booklib://oauth2-callback#done")
        ))
                .hasMessageContaining("Redirect URI must not contain a fragment");

        verify(appSettingsRepository, never()).save(any());
    }

    @Test
    void updateSetting_rejectsRedirectUriWithoutScheme() {
        assertThatThrownBy(() -> appSettingService.updateSetting(
                AppSettingKey.OIDC_REDIRECT_URIS,
                List.of("oauth2-callback")
        ))
                .hasMessageContaining("Redirect URI must include a scheme");

        verify(appSettingsRepository, never()).save(any());
    }

    @Test
    void updateSetting_acceptsValidRecommendationEmbeddingSettings() throws Exception {
        appSettingService.updateSetting(AppSettingKey.RECOMMENDATION_EMBEDDING_SETTINGS, Map.of(
                "ollamaBaseUrl", "http://ollama.internal:11434/",
                "model", "embeddinggemma:300m",
                "dimensions", 512,
                "batchSize", 64,
                "minSearchSimilarity", 0.55
        ));

        ArgumentCaptor<AppSettingEntity> settingCaptor = ArgumentCaptor.forClass(AppSettingEntity.class);
        verify(appSettingsRepository).save(settingCaptor.capture());

        AppSettingEntity savedSetting = settingCaptor.getValue();
        assertThat(savedSetting.getName()).isEqualTo(AppSettingKey.RECOMMENDATION_EMBEDDING_SETTINGS.toString());
        assertThat(savedSetting.getVal()).contains("\"ollamaBaseUrl\":\"http://ollama.internal:11434\"");
        assertThat(savedSetting.getVal()).contains("\"model\":\"embeddinggemma:300m\"");
        assertThat(savedSetting.getVal()).contains("\"dimensions\":512");
        assertThat(savedSetting.getVal()).contains("\"batchSize\":64");
        assertThat(savedSetting.getVal()).contains("\"minSearchSimilarity\":0.55");
    }

    // Settings rows written before semantic search existed have no minSearchSimilarity key. The API
    // must still hand the UI a concrete number, otherwise the settings input renders empty.
    @Test
    void getAppSettings_fillsMissingMinSearchSimilarityFromConfiguredDefault() {
        AppSettingEntity stored = new AppSettingEntity();
        stored.setName(AppSettingKey.RECOMMENDATION_EMBEDDING_SETTINGS.toString());
        stored.setVal("{\"ollamaBaseUrl\":\"http://ollama:11434\",\"model\":\"embeddinggemma:300m\","
                + "\"dimensions\":512,\"batchSize\":64}");
        when(appSettingsRepository.findAll()).thenReturn(List.of(stored));
        when(appProperties.getRemoteAuth()).thenReturn(new AppProperties.RemoteAuth());

        var settings = appSettingService.getAppSettings().getRecommendationEmbeddingSettings();

        assertThat(settings.getMinSearchSimilarity())
                .isEqualTo(recommendationEmbeddingProperties.getMinSearchSimilarity());
    }

    @Test
    void updateSetting_rejectsMinSearchSimilarityAboveOne() {
        assertThatThrownBy(() -> appSettingService.updateSetting(
                AppSettingKey.RECOMMENDATION_EMBEDDING_SETTINGS,
                Map.of(
                        "ollamaBaseUrl", "http://ollama.internal:11434",
                        "model", "embeddinggemma:300m",
                        "dimensions", 512,
                        "batchSize", 64,
                        "minSearchSimilarity", 1.5
                )
        )).hasMessageContaining("Minimum search similarity must be between 0 and 1");

        verify(appSettingsRepository, never()).save(any());
    }

    @Test
    void updateSetting_rejectsRecommendationEmbeddingUrlWithoutHost() {
        assertThatThrownBy(() -> appSettingService.updateSetting(
                AppSettingKey.RECOMMENDATION_EMBEDDING_SETTINGS,
                Map.of(
                        "ollamaBaseUrl", "http://",
                        "model", "qwen3-embedding:0.6b",
                        "dimensions", 512,
                        "batchSize", 64
                )
        )).hasMessageContaining("Ollama base URL is invalid");

        verify(appSettingsRepository, never()).save(any());
    }

    @Test
    void updateSetting_rejectsUnsupportedRecommendationEmbeddingDimensions() {
        assertThatThrownBy(() -> appSettingService.updateSetting(
                AppSettingKey.RECOMMENDATION_EMBEDDING_SETTINGS,
                Map.of(
                        "ollamaBaseUrl", "http://ollama.internal:11434",
                        "model", "qwen3-embedding:0.6b",
                        "dimensions", 384,
                        "batchSize", 64
                )
        )).hasMessageContaining("requires 512 dimensions");

        verify(appSettingsRepository, never()).save(any());
    }
}
