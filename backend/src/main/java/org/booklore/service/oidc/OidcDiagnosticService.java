package org.booklore.service.oidc;

import com.nimbusds.jose.jwk.JWKSet;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.OidcProviderDetails;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.booklore.util.FileUtils;

@Slf4j
@Service
@AllArgsConstructor
public class OidcDiagnosticService {

    public record OidcTestResult(boolean success, List<OidcTestCheck> checks) {}

    public record OidcTestCheck(String name, CheckStatus status, String message) {}

    public enum CheckStatus { PASS, FAIL, WARN, SKIP }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final String CHECK_DISCOVERY_DOCUMENT = "Discovery Document";
    private static final String NOT_FOUND_IN_DISCOVERY_DOCUMENT = "Not found in discovery document";
    private static final String CHECK_JWKS_KEYS = "JWKS Keys";
    private static final String CHECK_REQUIRED_SCOPES = "Required Scopes";
    private static final String CHECK_RESPONSE_TYPE_CODE = "Response Type 'code'";
    private static final String CHECK_PKCE_S256 = "PKCE (S256)";

    public OidcTestResult testConnection(OidcProviderDetails providerDetails) {
        List<OidcTestCheck> checks = new ArrayList<>();

        // 1. Fetch discovery document (uncached)
        Map<String, Object> doc = fetchDiscoveryDocument(providerDetails, checks);
        if (doc.isEmpty()) {
            return new OidcTestResult(false, checks);
        }

        boolean hasFailure = false;
        // 2. Check required endpoints
        hasFailure |= checkRequiredEndpoint(checks, doc, "authorization_endpoint", "Authorization Endpoint");
        hasFailure |= checkRequiredEndpoint(checks, doc, "token_endpoint", "Token Endpoint");
        hasFailure |= checkRequiredEndpoint(checks, doc, "jwks_uri", "JWKS URI");
        // 3. Fetch JWKS
        hasFailure |= checkJwksKeys(checks, doc);
        // 4. Check scopes
        checkRequiredScopes(checks, doc);
        // 5. Check response types
        hasFailure |= checkResponseTypes(checks, doc);
        // 6. Check PKCE
        checkPkce(checks, doc);
        // 7. Logout endpoints (informational)
        checkLogoutEndpoints(checks, doc);

        return new OidcTestResult(!hasFailure, checks);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchDiscoveryDocument(OidcProviderDetails providerDetails, List<OidcTestCheck> checks) {
        try {
            String issuerUri = FileUtils.trimTrailingSlashes(providerDetails.getIssuerUri());
            String discoveryUrl = issuerUri + "/.well-known/openid-configuration";

            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
            factory.setReadTimeout(READ_TIMEOUT_MS);

            var restClient = RestClient.builder().requestFactory(factory).build();
            Map<String, Object> doc = restClient.get().uri(discoveryUrl).retrieve().body(Map.class);

            if (doc == null) {
                checks.add(new OidcTestCheck(CHECK_DISCOVERY_DOCUMENT, CheckStatus.FAIL, "Empty response from discovery endpoint"));
                return Map.of();
            }
            checks.add(new OidcTestCheck(CHECK_DISCOVERY_DOCUMENT, CheckStatus.PASS, "Successfully fetched from " + discoveryUrl));
            return doc;
        } catch (Exception e) {
            checks.add(new OidcTestCheck(CHECK_DISCOVERY_DOCUMENT, CheckStatus.FAIL, "Failed to fetch: " + e.getMessage()));
            return Map.of();
        }
    }

    private boolean checkRequiredEndpoint(List<OidcTestCheck> checks, Map<String, Object> doc, String key, String name) {
        String value = (String) doc.get(key);
        if (value != null && !value.isBlank()) {
            checks.add(new OidcTestCheck(name, CheckStatus.PASS, value));
            return false;
        }
        checks.add(new OidcTestCheck(name, CheckStatus.FAIL, NOT_FOUND_IN_DISCOVERY_DOCUMENT));
        return true;
    }

    private boolean checkJwksKeys(List<OidcTestCheck> checks, Map<String, Object> doc) {
        String jwksUri = (String) doc.get("jwks_uri");
        if (jwksUri == null || jwksUri.isBlank()) {
            checks.add(new OidcTestCheck(CHECK_JWKS_KEYS, CheckStatus.SKIP, "Skipped (no JWKS URI)"));
            return false;
        }
        try {
            JWKSet jwkSet = JWKSet.load(URI.create(jwksUri).toURL());
            int keyCount = jwkSet.getKeys().size();
            checks.add(new OidcTestCheck(CHECK_JWKS_KEYS, CheckStatus.PASS, keyCount + " key(s) found"));
            return false;
        } catch (Exception e) {
            checks.add(new OidcTestCheck(CHECK_JWKS_KEYS, CheckStatus.FAIL, "Failed to fetch JWKS: " + e.getMessage()));
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private void checkRequiredScopes(List<OidcTestCheck> checks, Map<String, Object> doc) {
        List<String> scopes = (List<String>) doc.get("scopes_supported");
        if (scopes == null) {
            checks.add(new OidcTestCheck(CHECK_REQUIRED_SCOPES, CheckStatus.WARN, "scopes_supported not listed in discovery document"));
            return;
        }
        List<String> required = List.of("openid", "profile", "email");
        List<String> missing = required.stream().filter(s -> !scopes.contains(s)).toList();
        if (missing.isEmpty()) {
            checks.add(new OidcTestCheck(CHECK_REQUIRED_SCOPES, CheckStatus.PASS, "openid, profile, email all supported"));
        } else {
            checks.add(new OidcTestCheck(CHECK_REQUIRED_SCOPES, CheckStatus.WARN, "Missing scopes: " + String.join(", ", missing)));
        }
    }

    @SuppressWarnings("unchecked")
    private boolean checkResponseTypes(List<OidcTestCheck> checks, Map<String, Object> doc) {
        List<String> responseTypes = (List<String>) doc.get("response_types_supported");
        if (responseTypes != null && responseTypes.contains("code")) {
            checks.add(new OidcTestCheck(CHECK_RESPONSE_TYPE_CODE, CheckStatus.PASS, "Authorization code flow supported"));
            return false;
        } else if (responseTypes != null) {
            checks.add(new OidcTestCheck(CHECK_RESPONSE_TYPE_CODE, CheckStatus.FAIL, "Authorization code flow not supported"));
            return true;
        } else {
            checks.add(new OidcTestCheck(CHECK_RESPONSE_TYPE_CODE, CheckStatus.WARN, "response_types_supported not listed"));
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void checkPkce(List<OidcTestCheck> checks, Map<String, Object> doc) {
        List<String> codeChallengeMethodsSupported = (List<String>) doc.get("code_challenge_methods_supported");
        if (codeChallengeMethodsSupported != null && codeChallengeMethodsSupported.contains("S256")) {
            checks.add(new OidcTestCheck(CHECK_PKCE_S256, CheckStatus.PASS, "S256 code challenge method supported"));
        } else if (codeChallengeMethodsSupported != null) {
            checks.add(new OidcTestCheck(CHECK_PKCE_S256, CheckStatus.WARN, "S256 not listed, available: " + String.join(", ", codeChallengeMethodsSupported)));
        } else {
            checks.add(new OidcTestCheck(CHECK_PKCE_S256, CheckStatus.WARN, "code_challenge_methods_supported not listed (PKCE may still work)"));
        }
    }

    private void checkLogoutEndpoints(List<OidcTestCheck> checks, Map<String, Object> doc) {
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
