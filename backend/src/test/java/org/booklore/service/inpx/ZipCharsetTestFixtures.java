package org.booklore.service.inpx;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream.UnicodeExtraFieldPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

final class ZipCharsetTestFixtures {

    private ZipCharsetTestFixtures() {
    }

    static Path write(Path path, String charset, boolean languageEncodingFlag,
                      UnicodeExtraFieldPolicy unicodePolicy, Entry... entries) throws IOException {
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(path)) {
            configure(output, charset, languageEncodingFlag, unicodePolicy);
            writeEntries(output, entries);
        }
        return path;
    }

    static byte[] bytes(String charset, boolean languageEncodingFlag,
                        UnicodeExtraFieldPolicy unicodePolicy, Entry... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(bytes)) {
            configure(output, charset, languageEncodingFlag, unicodePolicy);
            writeEntries(output, entries);
        }
        return bytes.toByteArray();
    }

    static Entry entry(String name, String content) {
        return new Entry(name, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static Entry entry(String name, byte[] content) {
        return new Entry(name, content);
    }

    private static void configure(ZipArchiveOutputStream output, String charset,
                                  boolean languageEncodingFlag, UnicodeExtraFieldPolicy unicodePolicy) {
        output.setEncoding(charset);
        output.setUseLanguageEncodingFlag(languageEncodingFlag);
        output.setCreateUnicodeExtraFields(unicodePolicy);
    }

    private static void writeEntries(ZipArchiveOutputStream output, Entry... entries) throws IOException {
        for (Entry fixture : entries) {
            ZipArchiveEntry entry = new ZipArchiveEntry(fixture.name());
            output.putArchiveEntry(entry);
            output.write(fixture.content());
            output.closeArchiveEntry();
        }
    }

    record Entry(String name, byte[] content) {
        Entry {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
