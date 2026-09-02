package org.booklore.service.oidc;

import com.nimbusds.jose.jwk.JWKSet;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.OidcProviderDetails;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.booklore.util.FileUtils;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class OidcDiagnosticService {

    public record OidcTestResult(boolean success, List<OidcTestCheck> checks) {}

    public record OidcTestCheck(String name, CheckStatus status, String message) {}

    public enum CheckStatus { PASS, FAIL, WARN, SKIP }

    private final RestTemplate oidcRestTemplate;

    public OidcDiagnosticService(
            @Qualifier("oidcRestTemplate")
            RestTemplate oidcRestTemplate
    ) {
        this.oidcRestTemplate = oidcRestTemplate;
    }

    private static final String DISCOVERY_DOCUMENT = "Discovery Document";
    private static final String JWKS_KEYS = "JWKS Keys";
    private static final String REQUIRED_SCOPES = "Required Scopes";
    private static final String RESPONSE_TYPE_CODE = "Response Type 'code'";
    private static final String PKCE_S256 = "PKCE (S256)";
    private static final String NOT_IN_DISCOVERY_DOCUMENT = "Not found in discovery document";
    private static final List<String> REQUIRED_SCOPE_NAMES = List.of("openid", "profile", "email");

    /**
     * Runs the checks in a fixed order and reports each one; the result fails when any check does.
     * Only the discovery document is a hard stop, since every later check reads from it.
     */
    @SuppressWarnings("unchecked")
    public OidcTestResult testConnection(OidcProviderDetails providerDetails) {
        List<OidcTestCheck> checks = new ArrayList<>();
        Map<String, Object> doc = fetchDiscoveryDocument(providerDetails, checks);
        if (doc == null) {
            return new OidcTestResult(false, checks);
        }

        checkEndpoint(doc, "authorization_endpoint", "Authorization Endpoint", checks);
        checkEndpoint(doc, "token_endpoint", "Token Endpoint", checks);
        String jwksUri = checkEndpoint(doc, "jwks_uri", "JWKS URI", checks);
        checkJwks(jwksUri, checks);
        checkScopes((List<String>) doc.get("scopes_supported"), checks);
        checkResponseTypes((List<String>) doc.get("response_types_supported"), checks);
        checkPkce((List<String>) doc.get("code_challenge_methods_supported"), checks);
        checkLogout(doc, checks);

        boolean hasFailure = checks.stream().anyMatch(check -> check.status() == CheckStatus.FAIL);
        return new OidcTestResult(!hasFailure, checks);
    }

    /** Fetches the discovery document uncached. Returns {@code null} after recording why it could not be had. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchDiscoveryDocument(OidcProviderDetails providerDetails, List<OidcTestCheck> checks) {
        try {
            String issuerUri = FileUtils.trimTrailingSlashes(providerDetails.getIssuerUri());
            String discoveryUrl = issuerUri + "/.well-known/openid-configuration";

            Map<String, Object> doc = oidcRestTemplate.getForObject(discoveryUrl, Map.class);
            if (doc == null) {
                checks.add(new OidcTestCheck(DISCOVERY_DOCUMENT, CheckStatus.FAIL, "Empty response from discovery endpoint"));
                return null;
            }
            checks.add(new OidcTestCheck(DISCOVERY_DOCUMENT, CheckStatus.PASS, "Successfully fetched from " + discoveryUrl));
            return doc;
        } catch (Exception e) {
            checks.add(new OidcTestCheck(DISCOVERY_DOCUMENT, CheckStatus.FAIL, "Failed to fetch: " + e.getMessage()));
            return null;
        }
    }

    /** @return the endpoint URL, or {@code null} when the discovery document does not list it */
    private String checkEndpoint(Map<String, Object> doc, String key, String checkName, List<OidcTestCheck> checks) {
        String value = (String) doc.get(key);
        if (value != null && !value.isBlank()) {
            checks.add(new OidcTestCheck(checkName, CheckStatus.PASS, value));
            return value;
        }
        checks.add(new OidcTestCheck(checkName, CheckStatus.FAIL, NOT_IN_DISCOVERY_DOCUMENT));
        return null;
    }

    @SuppressWarnings("unchecked")
    private void checkJwks(String jwksUri, List<OidcTestCheck> checks) {
        if (jwksUri == null) {
            checks.add(new OidcTestCheck(JWKS_KEYS, CheckStatus.SKIP, "Skipped (no JWKS URI)"));
            return;
        }
        try {
            Map<String, Object> jwksDoc = oidcRestTemplate.getForObject(jwksUri, Map.class);
            if (jwksDoc == null) {
                jwksDoc = Map.of();
            }
            int keyCount = JWKSet.parse(jwksDoc).getKeys().size();
            checks.add(new OidcTestCheck(JWKS_KEYS, CheckStatus.PASS, keyCount + " key(s) found"));
        } catch (Exception e) {
            checks.add(new OidcTestCheck(JWKS_KEYS, CheckStatus.FAIL, "Failed to fetch JWKS: " + e.getMessage()));
        }
    }

    private void checkScopes(List<String> scopes, List<OidcTestCheck> checks) {
        if (scopes == null) {
            checks.add(new OidcTestCheck(REQUIRED_SCOPES, CheckStatus.WARN, "scopes_supported not listed in discovery document"));
            return;
        }
        List<String> missing = REQUIRED_SCOPE_NAMES.stream().filter(scope -> !scopes.contains(scope)).toList();
        if (missing.isEmpty()) {
            checks.add(new OidcTestCheck(REQUIRED_SCOPES, CheckStatus.PASS, "openid, profile, email all supported"));
        } else {
            checks.add(new OidcTestCheck(REQUIRED_SCOPES, CheckStatus.WARN, "Missing scopes: " + String.join(", ", missing)));
        }
    }

    private void checkResponseTypes(List<String> responseTypes, List<OidcTestCheck> checks) {
        if (responseTypes == null) {
            checks.add(new OidcTestCheck(RESPONSE_TYPE_CODE, CheckStatus.WARN, "response_types_supported not listed"));
        } else if (responseTypes.contains("code")) {
            checks.add(new OidcTestCheck(RESPONSE_TYPE_CODE, CheckStatus.PASS, "Authorization code flow supported"));
        } else {
            checks.add(new OidcTestCheck(RESPONSE_TYPE_CODE, CheckStatus.FAIL, "Authorization code flow not supported"));
        }
    }

    private void checkPkce(List<String> codeChallengeMethods, List<OidcTestCheck> checks) {
        if (codeChallengeMethods == null) {
            checks.add(new OidcTestCheck(PKCE_S256, CheckStatus.WARN, "code_challenge_methods_supported not listed (PKCE may still work)"));
        } else if (codeChallengeMethods.contains("S256")) {
            checks.add(new OidcTestCheck(PKCE_S256, CheckStatus.PASS, "S256 code challenge method supported"));
        } else {
            checks.add(new OidcTestCheck(PKCE_S256, CheckStatus.WARN, "S256 not listed, available: " + String.join(", ", codeChallengeMethods)));
        }
    }

    /** Informational only: neither logout endpoint is required for sign-in to work. */
    private void checkLogout(Map<String, Object> doc, List<OidcTestCheck> checks) {
        String endSessionEndpoint = (String) doc.get("end_session_endpoint");
        if (endSessionEndpoint != null && !endSessionEndpoint.isBlank()) {
            checks.add(new OidcTestCheck("End Session Endpoint", CheckStatus.PASS, endSessionEndpoint));
        } else {
            checks.add(new OidcTestCheck("End Session Endpoint", CheckStatus.WARN, "Not available (RP-initiated logout won't work)"));
        }

        Object backchannelLogout = doc.get("backchannel_logout_supported");
        if (Boolean.TRUE.equals(backchannelLogout)) {
            checks.add(new OidcTestCheck("Back-Channel Logout", CheckStatus.PASS, "Supported by provider"));
        } else {
            checks.add(new OidcTestCheck("Back-Channel Logout", CheckStatus.WARN, "Not supported or not advertised"));
        }
    }
}
