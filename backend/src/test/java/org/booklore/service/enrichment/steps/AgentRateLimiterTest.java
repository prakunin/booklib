package org.booklore.service.enrichment.steps;

import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.SmartEnrichmentSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRateLimiterTest {

    private final AppSettingService appSettingService = mock(AppSettingService.class);

    private AgentRateLimiter limiterWith(Integer configuredLimit) {
        SmartEnrichmentSettings.SmartEnrichmentSettingsBuilder settings = SmartEnrichmentSettings.builder();
        if (configuredLimit != null) {
            settings.maxAgentCallsPerHour(configuredLimit);
        }
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder()
                .smartEnrichmentSettings(settings.build())
                .build());
        return new AgentRateLimiter(appSettingService);
    }

    @Test
    void allowsUpToTheConfiguredLimitAndThenStops() {
        AgentRateLimiter limiter = limiterWith(3);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.remaining()).isZero();
    }

    /**
     * A settings row written before this field existed carries zero. Reading that as "unlimited"
     * would uncap spending on exactly the instances that never opted into it.
     */
    @Test
    void treatsAnUnsetLimitAsTheConservativeDefault() {
        AgentRateLimiter limiter = limiterWith(null);

        assertThat(limiter.remaining()).isEqualTo(AgentRateLimiter.DEFAULT_LIMIT);
    }

    @Test
    void treatsAnAbsentSettingsRowAsTheConservativeDefault() {
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder().build());

        assertThat(new AgentRateLimiter(appSettingService).remaining()).isEqualTo(AgentRateLimiter.DEFAULT_LIMIT);
    }

    @Test
    void allowsRemovingTheCapExplicitly() {
        AgentRateLimiter limiter = limiterWith(-1);

        for (int call = 0; call < AgentRateLimiter.DEFAULT_LIMIT + 5; call++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(limiter.remaining()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void reportsHowManyCallsAreLeft() {
        AgentRateLimiter limiter = limiterWith(5);

        limiter.tryAcquire();
        limiter.tryAcquire();

        assertThat(limiter.remaining()).isEqualTo(3);
    }
}
