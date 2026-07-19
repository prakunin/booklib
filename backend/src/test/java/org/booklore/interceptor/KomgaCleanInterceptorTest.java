package org.booklore.interceptor;

import org.booklore.context.KomgaCleanContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class KomgaCleanInterceptorTest {

    private KomgaCleanInterceptor interceptor;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        interceptor = new KomgaCleanInterceptor();
    }

    @AfterEach
    void cleanup() {
        KomgaCleanContext.clear();
    }

    @ParameterizedTest(name = "clean param [{1}] on {0} enables clean mode")
    @CsvSource({
            "/komga/api/v1/series, ''",
            "/komga/api/v1/series, true",
            "/komga/api/v1/books/123, TRUE"
    })
    void shouldEnableCleanModeWithParameterVariants(String uri, String cleanParam) throws Exception {
        // Given: Request with ?clean present, ?clean=true, or ?clean=TRUE
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getParameter("clean")).thenReturn(cleanParam);

        // When: Interceptor processes request
        interceptor.preHandle(request, response, new Object());

        // Then: Clean mode should be enabled
        assertThat(KomgaCleanContext.isCleanMode()).isTrue();
    }

    @ParameterizedTest(name = "clean param [{1}] on {0} does not enable clean mode")
    @CsvSource(value = {
            "/komga/api/v1/series, null",
            "/komga/api/v1/series, false",
            "/api/v1/books, true"
    }, nullValues = "null")
    void shouldNotEnableCleanModeVariants(String uri, String cleanParam) throws Exception {
        // Given: Request without clean parameter, with ?clean=false, or a non-Komga endpoint
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getParameter("clean")).thenReturn(cleanParam);

        // When: Interceptor processes request
        interceptor.preHandle(request, response, new Object());

        // Then: Clean mode should not be enabled
        assertThat(KomgaCleanContext.isCleanMode()).isFalse();
    }

    @Test
    void shouldClearContextAfterCompletion() throws Exception {
        // Given: Clean mode is enabled
        KomgaCleanContext.setCleanMode(true);
        assertThat(KomgaCleanContext.isCleanMode()).isTrue();

        // When: After completion is called
        interceptor.afterCompletion(request, response, new Object(), null);

        // Then: Context should be cleared
        assertThat(KomgaCleanContext.isCleanMode()).isFalse();
    }
}
