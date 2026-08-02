package org.booklore.model.dto.inpx;

import lombok.Builder;
import lombok.Data;
import org.booklore.model.enums.BookFileType;

import java.util.List;

@Data
@Builder
public class InpxBookDto {
    private String id;
    private List<String> authors;
    private List<String> genres;
    private String title;
    private String series;
    private String seriesNumber;
    private String fileName;
    private String extension;
    private String libraryId;
    private String date;
    private String language;
    private Double rating;
    private String archiveName;
    // Exact source identity. Null keeps the legacy direct-entry fileName + extension identity.
    private String sourceArchiveEntry;
    private Long fileSizeKb;
    // The file type inferred from the entry extension: a readable type (FB2, PDF, …) or the
    // download-only OTHER. Index-sourced books (InpxParser) are always FB2; archive-scanned books
    // (InpxArchiveScanner) carry their real format. Null is treated as FB2 for backward compatibility.
    private BookFileType bookType;
}
