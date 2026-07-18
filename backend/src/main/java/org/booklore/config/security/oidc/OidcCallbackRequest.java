package org.booklore.config.security.oidc;

import jakarta.validation.constraints.NotBlank;

public record OidcCallbackRequest(
        @NotBlank String code,
        @NotBlank String redirectUri,
        @NotBlank String state
) {}
