package org.booklore.service.koreader;

import org.booklore.util.Md5Util;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class KoreaderCredentialServiceTest {

    private final KoreaderCredentialService service = new KoreaderCredentialService(new BCryptPasswordEncoder());

    @Test
    void hashesRawPasswordAsSaltedWireKeyHash() {
        String wireKey = Md5Util.md5Hex("secret");

        String storedHash = service.hashRawPassword("secret");

        assertThat(storedHash).isNotEqualTo("secret").isNotEqualTo(wireKey);
        assertThat(service.matches(wireKey, storedHash)).isTrue();
    }

    @Test
    void supportsLegacyMd5HashForUpgrade() {
        String legacyHash = Md5Util.md5Hex("secret");

        assertThat(service.isLegacyMd5Hash(legacyHash)).isTrue();
        assertThat(service.matches(legacyHash, legacyHash)).isTrue();
    }
}
