package org.booklore.config.security.oidc;

import org.booklore.exception.APIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcStateServiceTest {

    private OidcStateService oidcStateService;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        oidcStateService = new OidcStateService();
        request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession(null, "session-1"));
    }

    @Test
    void generateState_returnsAuthorizationState() {
        OidcStateService.OidcAuthorizationState authorizationState = oidcStateService.generateState(request);

        assertThat(authorizationState.state()).isNotNull().isNotBlank();
        assertThat(authorizationState.nonce()).isNotNull().isNotBlank();
        assertThat(authorizationState.codeChallenge()).isNotNull().isNotBlank();
        assertThat(authorizationState.codeChallengeMethod()).isEqualTo("S256");
    }

    @Test
    void generateState_returnsUniqueValuesOnMultipleCalls() {
        Set<String> states = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            states.add(oidcStateService.generateState(request).state());
        }

        assertThat(states).hasSize(100);
    }

    @Test
    void generateState_returnsBase64UrlEncodedString() {
        OidcStateService.OidcAuthorizationState authorizationState = oidcStateService.generateState(request);

        assertThat(authorizationState.state()).matches("^[A-Za-z0-9_-]{43,44}$");
        assertThat(authorizationState.nonce()).matches("^[A-Za-z0-9_-]{43,44}$");
        assertThat(authorizationState.codeChallenge()).matches("^[A-Za-z0-9_-]{43,44}$");
    }

    @Test
    void validateAndConsume_returnsServerGeneratedFlowForValidGeneratedState() {
        OidcStateService.OidcAuthorizationState authorizationState = oidcStateService.generateState(request);

        OidcStateService.OidcAuthorizationFlow flow = oidcStateService.validateAndConsume(authorizationState.state(), request);

        assertThat(flow.codeVerifier()).matches("^[A-Za-z0-9_-]{43,44}$");
        assertThat(flow.nonce()).isEqualTo(authorizationState.nonce());
    }

    @Test
    void validateAndConsume_throwsForNullState() {
        assertThatThrownBy(() -> oidcStateService.validateAndConsume(null, request))
                .isInstanceOf(APIException.class);
    }

    @Test
    void validateAndConsume_throwsForBlankState() {
        assertThatThrownBy(() -> oidcStateService.validateAndConsume("   ", request))
                .isInstanceOf(APIException.class);
    }

    @Test
    void validateAndConsume_throwsForUnknownState() {
        assertThatThrownBy(() -> oidcStateService.validateAndConsume("unknown-state-value", request))
                .isInstanceOf(APIException.class);
    }

    @Test
    void validateAndConsume_stateIsSingleUse() {
        String state = oidcStateService.generateState(request).state();

        oidcStateService.validateAndConsume(state, request);

        assertThatThrownBy(() -> oidcStateService.validateAndConsume(state, request))
                .isInstanceOf(APIException.class);
    }

    @Test
    void validateAndConsume_multipleStatesCoexistIndependently() {
        String state1 = oidcStateService.generateState(request).state();
        String state2 = oidcStateService.generateState(request).state();
        String state3 = oidcStateService.generateState(request).state();

        oidcStateService.validateAndConsume(state2, request);

        oidcStateService.validateAndConsume(state1, request);
        oidcStateService.validateAndConsume(state3, request);
    }

    @Test
    void validateAndConsume_rejectsStateFromAnotherSession() {
        String state = oidcStateService.generateState(request).state();
        MockHttpServletRequest otherRequest = new MockHttpServletRequest();
        otherRequest.setSession(new MockHttpSession(null, "session-2"));

        assertThatThrownBy(() -> oidcStateService.validateAndConsume(state, otherRequest))
                .isInstanceOf(APIException.class);
    }
}
