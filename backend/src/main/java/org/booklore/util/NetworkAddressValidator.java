package org.booklore.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

public final class NetworkAddressValidator {

    private NetworkAddressValidator() {
    }

    public static void validateExternalHttpUrl(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + url, e);
        }

        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Only HTTP and HTTPS protocols are allowed");
        }

        String host = uri.getHost();
        if (host == null) {
            throw new IOException("Invalid URL: no host found in " + url);
        }

        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new IOException("Could not resolve host: " + host);
        }
        for (InetAddress address : addresses) {
            if (isInternalAddress(address)) {
                throw new SecurityException("URL points to a local or private internal network address: "
                        + host + " (" + address.getHostAddress() + ")");
            }
        }
    }

    public static boolean isInternalAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() ||
                address.isSiteLocalAddress() || address.isAnyLocalAddress()) {
            return true;
        }

        byte[] addr = address.getAddress();
        if (addr.length == 16 && (addr[0] & 0xFE) == 0xFC) {
            return true;
        }

        if (isIpv4MappedAddress(addr)) {
            try {
                byte[] ipv4Bytes = new byte[4];
                System.arraycopy(addr, 12, ipv4Bytes, 0, 4);
                return isInternalAddress(InetAddress.getByAddress(ipv4Bytes));
            } catch (UnknownHostException e) {
                return false;
            }
        }

        return false;
    }

    private static boolean isIpv4MappedAddress(byte[] addr) {
        if (addr.length != 16) return false;
        for (int i = 0; i < 10; i++) {
            if (addr[i] != 0) return false;
        }
        return (addr[10] == (byte) 0xFF) && (addr[11] == (byte) 0xFF);
    }
}
