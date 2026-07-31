package org.booklore.service.djvu;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * Decodes the binary PPM ({@code P6}) that {@code ddjvu} writes to stdout.
 * <p>
 * PPM rather than TIFF because {@code ddjvu} refuses to write TIFF anywhere but a named file
 * ("TIFF output requires a valid output file name"), and rendering through a temp file would put a
 * second full-page write on every page turn. PPM is the one format it will stream, and its header
 * is small enough to parse here that pulling in an image library to read it would be the larger
 * cost.
 */
final class PpmImage {

    private PpmImage() {
    }

    static BufferedImage decode(byte[] ppm) {
        Cursor cursor = new Cursor(ppm);
        if (!"P6".equals(cursor.nextToken())) {
            throw new DjvuToolException("Expected a binary PPM (P6) from ddjvu");
        }
        int width = cursor.nextInt();
        int height = cursor.nextInt();
        int maxValue = cursor.nextInt();
        if (maxValue != 255) {
            throw new DjvuToolException("Unsupported PPM max value " + maxValue + "; expected 255");
        }
        // Exactly one whitespace byte separates the header from the pixels.
        cursor.skipSingleWhitespace();

        int expected = Math.multiplyExact(Math.multiplyExact(width, height), 3);
        if (ppm.length - cursor.position() < expected) {
            throw new DjvuToolException("Truncated PPM: expected " + expected + " pixel bytes, got "
                    + (ppm.length - cursor.position()));
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        // PPM is RGB, TYPE_3BYTE_BGR is BGR: copy with the two outer channels swapped.
        for (int i = 0; i < expected; i += 3) {
            int source = cursor.position() + i;
            target[i] = ppm[source + 2];
            target[i + 1] = ppm[source + 1];
            target[i + 2] = ppm[source];
        }
        return image;
    }

    private static final class Cursor {

        private final byte[] data;
        private int position;

        private Cursor(byte[] data) {
            this.data = data;
        }

        private int position() {
            return position;
        }

        private String nextToken() {
            skipWhitespaceAndComments();
            int start = position;
            while (position < data.length && !isWhitespace(data[position])) {
                position++;
            }
            if (start == position) {
                throw new DjvuToolException("Malformed PPM header from ddjvu");
            }
            return new String(data, start, position - start, java.nio.charset.StandardCharsets.US_ASCII);
        }

        private int nextInt() {
            String token = nextToken();
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw new DjvuToolException("Malformed PPM header value: " + token, e);
            }
        }

        private void skipSingleWhitespace() {
            if (position < data.length && isWhitespace(data[position])) {
                position++;
            }
        }

        private void skipWhitespaceAndComments() {
            while (position < data.length) {
                if (isWhitespace(data[position])) {
                    position++;
                } else if (data[position] == '#') {
                    while (position < data.length && data[position] != '\n') {
                        position++;
                    }
                } else {
                    return;
                }
            }
        }

        private boolean isWhitespace(byte b) {
            return b == ' ' || b == '\n' || b == '\r' || b == '\t';
        }
    }
}
