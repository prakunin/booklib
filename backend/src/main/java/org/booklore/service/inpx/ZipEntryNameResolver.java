package org.booklore.service.inpx;

import org.apache.commons.compress.archivers.zip.UnicodePathExtraField;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipExtraField;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

final class ZipEntryNameResolver {

    private static final Charset IBM866 = Charset.forName("IBM866");
    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");
    private static final int MIN_LEGACY_SCORE = 6;
    private static final int MIN_SCORE_MARGIN = 3;

    private ZipEntryNameResolver() {
    }

    static String resolve(ZipArchiveEntry entry) {
        if (entry.getNameSource() == ZipArchiveEntry.NameSource.NAME_WITH_EFS_FLAG
                || entry.getNameSource() == ZipArchiveEntry.NameSource.UNICODE_EXTRA_FIELD) {
            return entry.getName();
        }

        byte[] rawName = entry.getRawName();
        if (rawName == null) {
            return entry.getName();
        }
        String declared = unicodePathName(entry, rawName);
        if (declared != null) {
            return declared;
        }
        String utf8 = decodeUtf8(rawName);
        if (utf8 != null) {
            return utf8;
        }

        String ibm866 = new String(rawName, IBM866);
        String windows1251 = new String(rawName, WINDOWS_1251);
        return chooseLegacy(entry.getName(), ibm866, windows1251);
    }

    static ZipArchiveEntry findEntry(ZipFile archive, String resolvedName) {
        Enumeration<ZipArchiveEntry> entries = archive.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            if (resolve(entry).equals(resolvedName)) {
                return entry;
            }
        }
        return null;
    }

    static Map<String, ZipArchiveEntry> indexEntries(ZipFile archive) {
        Map<String, ZipArchiveEntry> entriesByName = new LinkedHashMap<>();
        Enumeration<ZipArchiveEntry> entries = archive.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            entriesByName.putIfAbsent(resolve(entry), entry);
        }
        return entriesByName;
    }

    /**
     * The name the archive itself declares in a Unicode path extra field, when it declares one that
     * matches this entry.
     * <p>
     * Commons Compress normally applies this during its local-file-header pass and reports the
     * result as {@code NameSource.UNICODE_EXTRA_FIELD}, so the branch above would have caught it.
     * {@link LibraryArchives} skips that pass — it costs minutes on a library archive — which leaves
     * the field sitting unread on the entry, parsed from the central directory but never consulted.
     * Reading it here keeps naming identical whichever way the archive was opened, and keeps a
     * declared name ahead of the guesswork below, where it belongs.
     * <p>
     * The CRC guard is the same one Commons Compress applies: the extra field records a checksum of
     * the name it was written for, and an extra field left over from a renamed entry describes a
     * name this entry no longer has.
     */
    private static String unicodePathName(ZipArchiveEntry entry, byte[] rawName) {
        ZipExtraField field = entry.getExtraField(UnicodePathExtraField.UPATH_ID);
        if (!(field instanceof UnicodePathExtraField unicodePath)) {
            return null;
        }
        byte[] unicodeName = unicodePath.getUnicodeName();
        if (unicodeName == null) {
            return null;
        }
        CRC32 crc = new CRC32();
        crc.update(rawName);
        if (crc.getValue() != unicodePath.getNameCRC32()) {
            return null;
        }
        return decodeUtf8(unicodeName);
    }

    private static String decodeUtf8(byte[] rawName) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawName))
                    .toString();
        } catch (CharacterCodingException _) {
            return null;
        }
    }

    private static String chooseLegacy(String original, String ibm866, String windows1251) {
        int originalScore = score(original);
        int ibm866Score = score(ibm866);
        int windows1251Score = score(windows1251);
        if (ibm866Score >= MIN_LEGACY_SCORE
                && ibm866Score >= windows1251Score + MIN_SCORE_MARGIN
                && ibm866Score >= originalScore + MIN_SCORE_MARGIN) {
            return ibm866;
        }
        if (windows1251Score >= MIN_LEGACY_SCORE
                && windows1251Score >= ibm866Score + MIN_SCORE_MARGIN
                && windows1251Score >= originalScore + MIN_SCORE_MARGIN) {
            return windows1251;
        }
        return original;
    }

    private static int score(String value) {
        int score = 0;
        for (int index = 0; index < value.length(); index = value.offsetByCodePoints(index, 1)) {
            int codePoint = value.codePointAt(index);
            if (isUkrainianLetter(codePoint)) {
                score += 6;
            } else if (isRussianLetter(codePoint)) {
                score += 3;
            } else if (codePoint == 0xfffd) {
                score -= 8;
            } else if (Character.isISOControl(codePoint)) {
                score -= 6;
            } else if (isBoxDrawing(codePoint)) {
                score -= 5;
            } else if (isCyrillic(codePoint)) {
                score -= 2;
            }
        }
        return score;
    }

    private static boolean isRussianLetter(int codePoint) {
        return (codePoint >= 'А' && codePoint <= 'я') || codePoint == 'Ё' || codePoint == 'ё';
    }

    private static boolean isCyrillic(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.CYRILLIC;
    }

    private static boolean isUkrainianLetter(int codePoint) {
        return codePoint == 'Ґ' || codePoint == 'ґ'
                || codePoint == 'Є' || codePoint == 'є'
                || codePoint == 'І' || codePoint == 'і'
                || codePoint == 'Ї' || codePoint == 'ї';
    }

    private static boolean isBoxDrawing(int codePoint) {
        return codePoint >= 0x2500 && codePoint <= 0x259f;
    }
}
