package org.booklore.service.inpx;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InpxScanControlTest {

    private final InpxScanControl control = new InpxScanControl();

    @Test
    void reportsNoCancellationByDefault() {
        assertThat(control.isCancelRequested(1L)).isFalse();
    }

    @Test
    void remembersACancellationRequestPerLibrary() {
        control.requestCancel(1L);

        assertThat(control.isCancelRequested(1L)).isTrue();
        assertThat(control.isCancelRequested(2L)).isFalse();
    }

    @Test
    void clearingRemovesTheRequest() {
        control.requestCancel(1L);
        control.clear(1L);

        assertThat(control.isCancelRequested(1L)).isFalse();
    }
}
