package org.booklore.config.security.service;

import org.booklore.exception.APIException;
import org.booklore.service.audit.AuditService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AuthRateLimitServiceTest {

    private final AuthRateLimitService service = new AuthRateLimitService(mock(AuditService.class));

    @Test
    void alternateAuth_rateLimitsByIpAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            service.recordFailedAlternateAuthAttempt("opds", "10.0.0.10");
        }

        assertThatThrownBy(() -> service.checkAlternateAuthRateLimit("opds", "10.0.0.10"))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("Too many failed login attempts");
    }

    @Test
    void alternateAuth_rateLimitsByTrimmedCredentialAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            service.recordFailedAlternateAuthAttemptByCredential("koreader", " Reader ");
        }

        assertThatThrownBy(() -> service.checkAlternateAuthRateLimitByCredential("koreader", "Reader"))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("Too many failed login attempts");
    }

    @Test
    void alternateAuth_resetClearsIpAndCredentialFailures() {
        for (int i = 0; i < 5; i++) {
            service.recordFailedAlternateAuthAttempt("kobo", "10.0.0.11");
            service.recordFailedAlternateAuthAttemptByCredential("kobo", "token");
        }

        service.resetAlternateAuthAttempts("kobo", "10.0.0.11");
        service.resetAlternateAuthAttemptsByCredential("kobo", "token");

        service.checkAlternateAuthRateLimit("kobo", "10.0.0.11");
        service.checkAlternateAuthRateLimitByCredential("kobo", "token");
    }
}
