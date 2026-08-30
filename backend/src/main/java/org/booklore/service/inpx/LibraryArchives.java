package org.booklore.service.inpx;

import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Opens one of a library's ZIP archives for reading.
 * <p>
 * The whole reason this exists is {@code setIgnoreLocalFileHeader(true)}. By default Commons
 * Compress finishes {@code new ZipFile(...)} by walking the central directory and seeking to every
 * entry's local file header to record its data offset. That is one random read per entry before the
 * caller has asked for anything, and these archives are neither small nor local: measured on
 * {@code f.fb2-335441-338879.zip} — 2.4 GiB, 3153 entries, on the mounted books volume — opening
 * cost <b>226 seconds</b> with the default and <b>0.05 seconds</b> with the header ignored, for
 * byte-identical output from the entry that was actually wanted. A reader waiting out the first of
 * those looks hung, because for any human purpose it is.
 * <p>
 * Ignoring the local file header costs nothing here. The offset for the one entry being read is
 * resolved lazily by {@code getInputStream}, names and sizes come from the central directory, which
 * is authoritative, and the Unicode path extra field — the one thing the local-header pass would
 * otherwise apply — is read straight from the central directory by
 * {@link ZipEntryNameResolver#resolve}, so entry naming does not depend on how the archive was
 * opened.
 */
final class LibraryArchives {

    private LibraryArchives() {
    }

    static ZipFile open(Path archivePath) throws IOException {
        return ZipFile.builder()
                .setFile(archivePath.toFile())
                .setIgnoreLocalFileHeader(true)
                .get();
    }
}
