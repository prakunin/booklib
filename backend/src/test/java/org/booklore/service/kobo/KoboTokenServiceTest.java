package org.booklore.service.kobo;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KoboTokenServiceTest {

    private final KoboTokenService service = new KoboTokenService();

    @Test
    void generatedTokensAreUrlSafeAndHashable() {
        String token = service.generateToken();

        assertThat(token).doesNotContain("/", "+", "=");
        assertThat(service.hashToken(token)).hasSize(64);
        assertThat(service.hashToken(token)).isEqualTo(service.hashToken(token));
    }

    @Test
    void blankTokenCannotBeHashed() {
        assertThrows(IllegalArgumentException.class, () -> service.hashToken(" "));
    }

    @Test
    void detectsExpiredAndMissingExpiry() {
        assertThat(service.isExpired(null)).isTrue();
        assertThat(service.isExpired(Instant.now().minusSeconds(1))).isTrue();
        assertThat(service.isExpired(Instant.now().plusSeconds(60))).isFalse();
    }
}
