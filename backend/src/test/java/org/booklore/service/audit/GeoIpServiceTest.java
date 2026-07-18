package org.booklore.service.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeoIpServiceTest {

    @Mock
    private HttpClient httpClient;

    private GeoIpService service;

    @BeforeEach
    void setUp() {
        service = new GeoIpService(httpClient, new ObjectMapper());
    }

    @Test
    void cacheHasMaximumSizeAndTtl() {
        var eviction = service.cache().policy().eviction();
        var expiration = service.cache().policy().expireAfterWrite();

        assertThat(eviction).isPresent();
        assertThat(eviction.orElseThrow().getMaximum()).isEqualTo(GeoIpService.CACHE_MAX_SIZE);
        assertThat(expiration).isPresent();
        assertThat(expiration.orElseThrow().getExpiresAfter(TimeUnit.HOURS)).isEqualTo(GeoIpService.CACHE_TTL.toHours());
    }

    @Test
    void resolveCountryCodeCachesByNormalizedIp() throws Exception {
        givenGeoResponse("US");

        assertThat(service.resolveCountryCode(" 8.8.8.8 ")).isEqualTo("US");
        assertThat(service.resolveCountryCode("8.8.8.8")).isEqualTo("US");

        verify(httpClient, times(1)).send(any(HttpRequest.class), anyStringBodyHandler());
        assertThat(service.cache().getIfPresent("8.8.8.8")).isEqualTo("US");
    }

    @Test
    void resolveCountryCodeDoesNotCachePrivateOrInvalidInput() {
        assertThat(service.resolveCountryCode("127.0.0.1")).isNull();
        assertThat(service.resolveCountryCode("192.168.1.44")).isNull();
        assertThat(service.resolveCountryCode("localhost")).isNull();
        assertThat(service.resolveCountryCode("example.com")).isNull();

        verifyNoInteractions(httpClient);
        assertThat(service.cache().estimatedSize()).isZero();
    }

    @Test
    void resolveCountryCodeUsesNormalizedIpInRequest() throws Exception {
        givenGeoResponse("DE");

        assertThat(service.resolveCountryCode("2001:4860:4860::8888")).isEqualTo("DE");

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), anyStringBodyHandler());
        assertThat(request.getValue().uri().toString())
                .isEqualTo("http://ip-api.com/json/2001:4860:4860:0:0:0:0:8888?fields=countryCode");
    }

    @Test
    void resolveCountryCodeDoesNotCacheTransientFailures() throws Exception {
        HttpResponse<String> rateLimited = mock();
        when(rateLimited.statusCode()).thenReturn(429);

        HttpResponse<String> success = mock();
        when(success.statusCode()).thenReturn(200);
        when(success.body()).thenReturn("{\"countryCode\":\"US\"}");

        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                .thenReturn(rateLimited)
                .thenReturn(success);

        assertThat(service.resolveCountryCode("8.8.8.8")).isNull();
        assertThat(service.cache().getIfPresent("8.8.8.8")).isNull();

        assertThat(service.resolveCountryCode("8.8.8.8")).isEqualTo("US");

        verify(httpClient, times(2)).send(any(HttpRequest.class), anyStringBodyHandler());
        assertThat(service.cache().getIfPresent("8.8.8.8")).isEqualTo("US");
    }

    private void givenGeoResponse(String countryCode) throws Exception {
        HttpResponse<String> response = mock();
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"countryCode\":\"" + countryCode + "\"}");
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<String> anyStringBodyHandler() {
        return any(HttpResponse.BodyHandler.class);
    }
}
