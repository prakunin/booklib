package org.booklore.model.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

@Getter
public enum BookFileType {

    PDF(Set.of("pdf")),
    EPUB(Set.of("epub")),
    CBX(Set.of("cbz", "cbr", "cb7")),
    FB2(Set.of("fb2")),
    MOBI(Set.of("mobi")),
    AZW3(Set.of("azw3", "azw")),
    DJVU(Set.of("djvu", "djv")),
    AUDIOBOOK(Set.of("m4b", "m4a", "mp3", "opus")),
    // Word documents from library folders and BookDrop, which resolve through BookFileExtension.
    // Deliberately carries no extensions, for the same reason OTHER does not: fromExtension is used
    // only by the INPX archive path, and archive entries must keep resolving to OTHER.
    DOC(Set.of()),
    // Download-only catch-all for catalog entries whose format has no reader (rtf, …).
    // Deliberately carries no extensions: fromExtension never resolves to it, so only code that
    // explicitly chooses OTHER (the INPX archive ingest) ever assigns it.
    OTHER(Set.of());

    private final Set<String> extensions;

    BookFileType(Set<String> extensions) {
        this.extensions = extensions;
    }

    public boolean supports(String extension) {
        return extensions.contains(extension.toLowerCase());
    }

    public static Optional<BookFileType> fromName(String name) {
        if (name == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(name))
                .findFirst();
    }

    public static Optional<BookFileType> fromExtension(String extension) {
        String ext = extension.toLowerCase();
        return Arrays.stream(values())
                .filter(type -> type.supports(ext))
                .findFirst();
    }
}
