package org.booklore.service.audit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeoIpService {

    private static final String GEO_API_URL = "http://ip-api.com/json/%s?fields=countryCode";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    static final long CACHE_MAX_SIZE = 10_000;
    static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.%]+");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, String> cache = Caffeine.newBuilder()
            .maximumSize(CACHE_MAX_SIZE)
            .expireAfterWrite(CACHE_TTL)
            .build();

    public String resolveCountryCode(String ip) {
        String cacheKey = normalizePublicIp(ip);
        if (cacheKey == null) {
            return null;
        }
        String result = cache.get(cacheKey, this::fetchCountryCode);
        return result.isEmpty() ? null : result;
    }

    private String fetchCountryCode(String ip) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(GEO_API_URL, ip)))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                if (node.has("countryCode") && !node.get("countryCode").asText().isBlank()) {
                    return node.get("countryCode").asText();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while resolving country code for IP: {}", ip);
        } catch (Exception e) {
            log.debug("Failed to resolve country code for IP: {}", ip);
        }
        return "";
    }

    private String normalizePublicIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String candidate = ip.trim();
        if (!IP_LITERAL.matcher(candidate).matches()) {
            return null;
        }
        try {
            InetAddress addr = InetAddress.getByName(candidate);
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                return null;
            }
            return addr.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    Cache<String, String> cache() {
        return cache;
    }
}
