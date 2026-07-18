package org.booklore.config.security.oidc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcDiscoveryServiceTest {

    private final OidcDiscoveryService discoveryService = new OidcDiscoveryService();

    @Test
    void discover_rejectsPlainHttpIssuer() {
        assertThatThrownBy(() -> discoveryService.discover("http://issuer.example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void discover_rejectsLoopbackIssuer() {
        assertThatThrownBy(() -> discoveryService.discover("https://127.0.0.1"))
                .isInstanceOf(SecurityException.class);
    }
}
