package org.booklore.service.koreader;

import org.booklore.util.Md5Util;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Service
public class KoreaderCredentialService {

    private static final Pattern MD5_HEX = Pattern.compile("^[0-9a-fA-F]{32}$");

    private final PasswordEncoder passwordEncoder;

    public KoreaderCredentialService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public String hashRawPassword(String rawPassword) {
        return hashWireKey(Md5Util.md5Hex(rawPassword));
    }

    public String hashWireKey(String md5WireKey) {
        if (!isMd5Hex(md5WireKey)) {
            throw new IllegalArgumentException("Invalid KOReader MD5 key");
        }
        return passwordEncoder.encode(md5WireKey.toLowerCase());
    }

    public boolean matches(String md5WireKey, String storedHash) {
        if (!isMd5Hex(md5WireKey) || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (isLegacyMd5Hash(storedHash)) {
            return MessageDigest.isEqual(
                    HexFormat.of().parseHex(storedHash.toLowerCase()),
                    HexFormat.of().parseHex(md5WireKey.toLowerCase()));
        }
        return passwordEncoder.matches(md5WireKey.toLowerCase(), storedHash);
    }

    public boolean isLegacyMd5Hash(String storedHash) {
        return isMd5Hex(storedHash);
    }

    private boolean isMd5Hex(String value) {
        return value != null && MD5_HEX.matcher(value).matches();
    }
}
