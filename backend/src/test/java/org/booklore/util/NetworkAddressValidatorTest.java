package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkAddressValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/cover.jpg",
            "http://0.0.0.0/cover.jpg",
            "http://10.0.0.1/cover.jpg",
            "http://172.16.0.1/cover.jpg",
            "http://192.168.1.10/cover.jpg",
            "http://169.254.169.254/latest/meta-data/"
    })
    void blocksInternalIpv4Urls(String url) {
        assertThatThrownBy(() -> NetworkAddressValidator.validateExternalHttpUrl(url))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void allowsPublicIpv4Urls() {
        assertThatCode(() -> NetworkAddressValidator.validateExternalHttpUrl("http://93.184.216.34/cover.jpg"))
                .doesNotThrowAnyException();
    }

    @Test
    void detectsIpv6UniqueLocalAndMappedLoopbackAddresses() throws Exception {
        assertThat(NetworkAddressValidator.isInternalAddress(InetAddress.getByName("fc00::1"))).isTrue();
        assertThat(NetworkAddressValidator.isInternalAddress(InetAddress.getByName("::ffff:127.0.0.1"))).isTrue();
    }
}
