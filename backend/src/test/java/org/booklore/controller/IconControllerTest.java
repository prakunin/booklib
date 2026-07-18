package org.booklore.controller;

import org.booklore.service.IconService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IconControllerTest {

    private IconService iconService;
    private IconController controller;

    @BeforeEach
    void setUp() {
        iconService = mock(IconService.class);
        controller = new IconController(iconService);
    }

    @Test
    void svgIconContentUsesCacheHeadersAndEtag() {
        when(iconService.getSvgIcon("book")).thenReturn("<svg><path d=\"M0 0\"/></svg>");

        var response = controller.getSvgIconContent("book", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/svg+xml");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=600, private");
        assertThat(response.getHeaders().getETag()).matches("\"[0-9a-f]{64}\"");
        assertThat(response.getBody()).isEqualTo("<svg><path d=\"M0 0\"/></svg>");
    }

    @Test
    void svgIconContentReturnsNotModifiedForMatchingIfNoneMatch() {
        when(iconService.getSvgIcon("book")).thenReturn("<svg><path d=\"M0 0\"/></svg>");
        String etag = controller.getSvgIconContent("book", null).getHeaders().getETag();

        var response = controller.getSvgIconContent("book", etag);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=600, private");
        assertThat(response.getHeaders().getETag()).isEqualTo(etag);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void allIconsContentUsesStableEtagIndependentOfMapOrder() {
        Map<String, String> firstOrder = new LinkedHashMap<>();
        firstOrder.put("book", "<svg><path d=\"M0 0\"/></svg>");
        firstOrder.put("shelf", "<svg><circle r=\"1\"/></svg>");

        Map<String, String> secondOrder = new LinkedHashMap<>();
        secondOrder.put("shelf", "<svg><circle r=\"1\"/></svg>");
        secondOrder.put("book", "<svg><path d=\"M0 0\"/></svg>");

        when(iconService.getAllIconsContent()).thenReturn(firstOrder, secondOrder);
        String etag = controller.getAllIconsContent(null).getHeaders().getETag();

        var response = controller.getAllIconsContent("W/" + etag);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getHeaders().getETag()).isEqualTo(etag);
        assertThat(response.getBody()).isNull();
    }
}
