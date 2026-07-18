package org.booklore.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebMvcConfigTest {

    @Test
    void detectsHashedAngularBundlesForImmutableCaching() {
        assertThat(WebMvcConfig.isHashedAngularBundle("/main-ABCD1234.js")).isTrue();
        assertThat(WebMvcConfig.isHashedAngularBundle("/polyfills-a1B2c3D4.js")).isTrue();
        assertThat(WebMvcConfig.isHashedAngularBundle("/styles-abcdef123456.css")).isTrue();
        assertThat(WebMvcConfig.isHashedAngularBundle("/chunk-ZXCV_1234.js")).isTrue();
    }

    @Test
    void leavesNonHashedAndRuntimeServiceWorkerFilesUnmatched() {
        assertThat(WebMvcConfig.isHashedAngularBundle("/index.html")).isFalse();
        assertThat(WebMvcConfig.isHashedAngularBundle("/ngsw-worker.js")).isFalse();
        assertThat(WebMvcConfig.isHashedAngularBundle("/ngsw.json")).isFalse();
        assertThat(WebMvcConfig.isHashedAngularBundle("/main.js")).isFalse();
        assertThat(WebMvcConfig.isHashedAngularBundle("/api/v1/books/42/content")).isFalse();
    }
}
