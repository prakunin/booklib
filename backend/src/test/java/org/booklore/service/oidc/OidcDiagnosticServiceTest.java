package org.booklore.service.oidc;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.booklore.model.dto.settings.OidcProviderDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OidcDiagnosticService")
class OidcDiagnosticServiceTest {

    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";
    private static final String JWKS_PATH = "/jwks";
    private static final String AUTHORIZATION_ENDPOINT_CHECK = "Authorization Endpoint";
    private static final String TOKEN_ENDPOINT_CHECK = "Token Endpoint";
    private static final String JWKS_URI_CHECK = "JWKS URI";
    private static final String JWKS_KEYS_CHECK = "JWKS Keys";
    private static final String PKCE_CHECK = "PKCE (S256)";
    private static final String END_SESSION_CHECK = "End Session Endpoint";
    private static final String BACKCHANNEL_LOGOUT_CHECK = "Back-Channel Logout";

    private final OidcDiagnosticService service = new OidcDiagnosticService();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String startServer(int port, String discoveryJson, String jwksJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(DISCOVERY_PATH, exchange -> respondJson(exchange, discoveryJson));
        server.createContext(JWKS_PATH, exchange -> respondJson(exchange, jwksJson));
        server.start();
        return "http://127.0.0.1:" + port;
    }

    private void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private OidcProviderDetails providerDetails(String issuerUri) {
        OidcProviderDetails details = new OidcProviderDetails();
        details.setIssuerUri(issuerUri);
        return details;
    }

    private Optional<OidcDiagnosticService.OidcTestCheck> findCheck(List<OidcDiagnosticService.OidcTestCheck> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    private String generateJwksJson() throws Exception {
        RSAKey rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        return new JWKSet(rsaKey.toPublicJWK()).toString();
    }

    @Nested
    @DisplayName("testConnection")
    class TestConnection {

        @Test
        @DisplayName("full valid discovery document and reachable JWKS produces all-passing checks")
        void fullSuccess_allChecksPass() throws Exception {
            int port = findFreePort();
            String baseUrl = "http://127.0.0.1:" + port;
            String jwksJson = generateJwksJson();
            String discoveryJson = """
                    {
                      "authorization_endpoint": "%1$s/auth",
                      "token_endpoint": "%1$s/token",
                      "jwks_uri": "%1$s/jwks",
                      "scopes_supported": ["openid", "profile", "email"],
                      "response_types_supported": ["code"],
                      "code_challenge_methods_supported": ["S256"],
                      "end_session_endpoint": "%1$s/logout",
                      "backchannel_logout_supported": true
                    }
                    """.formatted(baseUrl);
            startServer(port, discoveryJson, jwksJson);

            OidcDiagnosticService.OidcTestResult result = service.testConnection(providerDetails(baseUrl));

            assertThat(result.success()).isTrue();
            assertThat(findCheck(result.checks(), "Discovery Document")).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
            assertThat(findCheck(result.checks(), AUTHORIZATION_ENDPOINT_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
            assertThat(findCheck(result.checks(), TOKEN_ENDPOINT_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
            assertThat(findCheck(result.checks(), JWKS_URI_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
            assertThat(findCheck(result.checks(), JWKS_KEYS_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
            assertThat(findCheck(result.checks(), JWKS_KEYS_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::message).isEqualTo("1 key(s) found");
            assertThat(findCheck(result.checks(), "Required Scopes")).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
            assertThat(findCheck(result.checks(), "Response Type 'code'")).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
            assertThat(findCheck(result.checks(), PKCE_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
            assertThat(findCheck(result.checks(), END_SESSION_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
            assertThat(findCheck(result.checks(), BACKCHANNEL_LOGOUT_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
        }

        @Test
        @DisplayName("unreachable discovery endpoint yields a single failing check")
        void discoveryDocumentUnreachable_returnsSingleFailureCheck() {
            OidcDiagnosticService.OidcTestResult result = service.testConnection(providerDetails("http://127.0.0.1:1"));

            assertThat(result.success()).isFalse();
            assertThat(result.checks()).hasSize(1);
            assertThat(result.checks().get(0).name()).isEqualTo("Discovery Document");
            assertThat(result.checks().get(0).status()).isEqualTo(OidcDiagnosticService.CheckStatus.FAIL);
        }

        @Test
        @DisplayName("missing authorization endpoint, scopes, and non-code-only response types flag failures and warnings")
        void partialDocument_flagsFailuresAndWarnings() throws Exception {
            int port = findFreePort();
            String baseUrl = "http://127.0.0.1:" + port;
            String discoveryJson = """
                    {
                      "token_endpoint": "%1$s/token",
                      "response_types_supported": ["token"],
                      "code_challenge_methods_supported": ["plain"]
                    }
                    """.formatted(baseUrl);
            startServer(port, discoveryJson, "{}");

            OidcDiagnosticService.OidcTestResult result = service.testConnection(providerDetails(baseUrl));

            assertThat(result.success()).isFalse();
            assertThat(findCheck(result.checks(), AUTHORIZATION_ENDPOINT_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.FAIL);
            assertThat(findCheck(result.checks(), TOKEN_ENDPOINT_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
            assertThat(findCheck(result.checks(), JWKS_KEYS_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.SKIP);
            assertThat(findCheck(result.checks(), "Required Scopes")).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.WARN);
            assertThat(findCheck(result.checks(), "Response Type 'code'")).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.FAIL);
            assertThat(findCheck(result.checks(), PKCE_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.WARN);
            assertThat(findCheck(result.checks(), END_SESSION_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.WARN);
            assertThat(findCheck(result.checks(), BACKCHANNEL_LOGOUT_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.WARN);
        }

        @Test
        @DisplayName("unreachable JWKS endpoint fails only the JWKS check")
        void jwksUriUnreachable_reportsFailureButOtherChecksPass() throws Exception {
            int port = findFreePort();
            String baseUrl = "http://127.0.0.1:" + port;
            String discoveryJson = """
                    {
                      "authorization_endpoint": "%1$s/auth",
                      "token_endpoint": "%1$s/token",
                      "jwks_uri": "http://127.0.0.1:1/jwks",
                      "scopes_supported": ["openid", "profile", "email"],
                      "response_types_supported": ["code"],
                      "code_challenge_methods_supported": ["S256"],
                      "end_session_endpoint": "%1$s/logout",
                      "backchannel_logout_supported": true
                    }
                    """.formatted(baseUrl);
            startServer(port, discoveryJson, "{}");

            OidcDiagnosticService.OidcTestResult result = service.testConnection(providerDetails(baseUrl));

            assertThat(result.success()).isFalse();
            assertThat(findCheck(result.checks(), AUTHORIZATION_ENDPOINT_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.PASS);
            assertThat(findCheck(result.checks(), JWKS_KEYS_CHECK)).get()
                    .extracting(OidcDiagnosticService.OidcTestCheck::status).isEqualTo(OidcDiagnosticService.CheckStatus.FAIL);
        }
    }
}
