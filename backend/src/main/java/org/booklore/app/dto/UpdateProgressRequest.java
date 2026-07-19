package org.booklore.app.dto;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.booklore.model.dto.progress.AudiobookProgress;
import org.booklore.model.dto.progress.CbxProgress;
import org.booklore.model.dto.progress.EpubProgress;
import org.booklore.model.dto.progress.PdfProgress;
import org.booklore.model.dto.request.BookFileProgress;

import java.time.Instant;

@Data
public class UpdateProgressRequest {

    private BookFileProgress fileProgress;

    /**
     * @deprecated Use per-file progress ({@link org.booklore.model.dto.request.BookFileProgress}) instead; kept for dual-write compatibility.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    @SuppressWarnings("java:S1133") // Deliberate dual-write compat field kept alongside per-file progress; remove with the legacy fields.
    private EpubProgress epubProgress;
    /**
     * @deprecated Use per-file progress ({@link org.booklore.model.dto.request.BookFileProgress}) instead; kept for dual-write compatibility.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    @SuppressWarnings("java:S1133") // Deliberate dual-write compat field kept alongside per-file progress; remove with the legacy fields.
    private PdfProgress pdfProgress;
    /**
     * @deprecated Use per-file progress ({@link org.booklore.model.dto.request.BookFileProgress}) instead; kept for dual-write compatibility.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    @SuppressWarnings("java:S1133") // Deliberate dual-write compat field kept alongside per-file progress; remove with the legacy fields.
    private CbxProgress cbxProgress;
    /**
     * @deprecated Use per-file progress ({@link org.booklore.model.dto.request.BookFileProgress}) instead; kept for dual-write compatibility.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    @SuppressWarnings("java:S1133") // Deliberate dual-write compat field kept alongside per-file progress; remove with the legacy fields.
    private AudiobookProgress audiobookProgress;

    private Instant dateFinished;

    @AssertTrue(message = "At least one progress field must be provided")
    // Deliberate use of the deprecated legacy per-format progress fields (dual-write compat); remove with the legacy columns.
    @SuppressWarnings("java:S1874")
    public boolean isProgressValid() {
        return fileProgress != null || epubProgress != null || pdfProgress != null || cbxProgress != null || audiobookProgress != null || dateFinished != null;
    }
}
