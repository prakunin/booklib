package org.booklore.config.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigCorsTest {

    @Test
    void defaultCorsDoesNotAllowCrossOriginCredentials() {
        CorsConfiguration configuration = configurationFor(new MockEnvironment());

        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void explicitOriginsAreAllowedWithCredentials() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.cors.allowed-origins", "https://books.example, https://app.example");

        CorsConfiguration configuration = configurationFor(env);

        assertThat(configuration.checkOrigin("https://books.example")).isEqualTo("https://books.example");
        assertThat(configuration.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void starOriginIsRejectedWhenCredentialsAreEnabled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.cors.allowed-origins", "*");

        assertThatThrownBy(() -> new SecurityConfig(null, null, null, env).corsConfigurationSource())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS cannot allow '*'");
    }

    private CorsConfiguration configurationFor(MockEnvironment env) {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/books");
        return new SecurityConfig(null, null, null, env)
                .corsConfigurationSource()
                .getCorsConfiguration(request);
    }
}
