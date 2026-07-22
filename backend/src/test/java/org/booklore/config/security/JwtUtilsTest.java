package org.booklore.config.security;

import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.service.security.JwtSecretService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtUtilsTest {

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        JwtSecretService jwtSecretService = mock(JwtSecretService.class);
        when(jwtSecretService.getSecret()).thenReturn("01234567890123456789012345678901");
        jwtUtils = new JwtUtils(jwtSecretService);
        jwtUtils.init();
    }

    @Test
    void generateMediaToken_validatesOnlyAsMediaToken() {
        BookLoreUserEntity user = user();

        String token = jwtUtils.generateMediaToken(user);

        assertThat(jwtUtils.validateMediaToken(token)).isTrue();
        assertThat(jwtUtils.validateAccessToken(token)).isFalse();
        assertThat(jwtUtils.extractUserId(token)).isEqualTo(42L);
    }

    @Test
    void generateAccessToken_canAuthenticateBrowserMediaRequests() {
        String token = jwtUtils.generateAccessToken(user());

        assertThat(jwtUtils.validateAccessToken(token)).isTrue();
        assertThat(jwtUtils.validateMediaToken(token)).isTrue();
    }

    private BookLoreUserEntity user() {
        return BookLoreUserEntity.builder()
                .id(42L)
                .username("reader")
                .isDefaultPassword(false)
                .build();
    }
}
