package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.convertor.BookRecommendationIdsListConverter;
import org.booklore.model.dto.BookRecommendationLite;
import org.booklore.model.enums.BookFileType;
import org.hibernate.Hibernate;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.LazyGroup;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "book")
public class BookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metadata_match_score")
    private Float metadataMatchScore;

    @OneToOne(mappedBy = "book", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private BookMetadataEntity metadata;

    @Column(name = "metadata_updated_at")
    private Instant metadataUpdatedAt;

    @Column(name = "metadata_for_write_updated_at")
    private Instant metadataForWriteUpdatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "library_id", nullable = false)
    private LibraryEntity library;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "library_path_id")
    private LibraryPathEntity libraryPath;

    @Column(name = "is_physical")
    @Builder.Default
    private Boolean isPhysical = Boolean.FALSE;

    /**
     * Denormalized mirror of "bookFiles is non-empty" so catalog predicates avoid the correlated
     * EXISTS over book_file (~2.5s on a 630k-book catalog in MariaDB). Every code path that
     * attaches or detaches a BookFileEntity must keep it in sync - via {@link #syncHasFiles()}
     * when the collection is loaded, or {@code setHasFiles} otherwise. Distinct from the
     * in-memory check {@link #hasFiles()}.
     */
    @Column(name = "has_files", nullable = false)
    @Builder.Default
    private Boolean hasFiles = Boolean.FALSE;

    @Column(name = "added_on")
    private Instant addedOn;

    @Column(name = "scanned_on")
    private Instant scannedOn;

    @Column(name = "book_cover_hash", length = 20)
    private String bookCoverHash;

    @Column(name = "audiobook_cover_hash", length = 20)
    private String audiobookCoverHash;

    /**
     * Set once a lazy INPX cover probe completes and establishes that this book will never yield a
     * cover from its archive: either the archived FB2 was read through and has no cover binary, or
     * it has one that can never be turned into an image (an SVG, say). The lazy path
     * ({@link org.booklore.service.metadata.BookCoverService#tryGenerateMissingInpxCover}) then
     * skips re-opening the archive. Cleared on rescan so an archive that gains a cover is picked up
     * again, and cleared by {@link #setBookCoverHash} the moment the book gets a cover by any route.
     * <p>
     * Set only on a completed probe, never on a failure to read - that is the whole point of the
     * column, and it is a real invariant rather than an aspiration only because
     * {@code Fb2MetadataExtractor.extractCover} distinguishes the two. It could not until recently:
     * it answered both "this FB2 has no cover" and "this FB2 could not be opened, parsed, or
     * base64-decoded" with a bare {@code null}, {@code Fb2Processor} mapped that {@code null} to
     * {@code NO_COVER_FOUND}, and a transient IO error was recorded here permanently. This javadoc
     * asserted the invariant throughout, which is exactly what stopped four fix waves from looking
     * here: the guarantee is only ever as good as the layer that can actually tell the difference.
     */
    @Column(name = "cover_probed_at")
    private Instant coverProbedAt;

    /**
     * Stamping a cover hash clears any {@link #coverProbedAt} marker: the marker means "a probe read
     * the source and it genuinely had no cover", which a book that now has one makes obsolete.
     * Leaving it set would permanently block the lazy probe if the hash were ever cleared without a
     * rescan.
     * <p>
     * This lives in the setter rather than in its callers because a dozen call sites stamp a hash -
     * the six file processors, the library scanner, the metadata updater, the migration and the cover
     * service - and keeping them in step by hand has already failed repeatedly. Hibernate hydrates
     * through field access ({@code @Id} is on the field), so overriding the setter does not affect
     * loading.
     * <p>
     * Two routes still write the fields directly and this setter cannot see them. {@link
     * BookEntityBuilder} is hand-patched below to close its half. {@code @AllArgsConstructor}
     * remains a genuine hole: {@code new BookEntity(...)} with every field positionally can still
     * produce a book holding both a hash and a marker. Nothing in the codebase calls it - Lombok
     * generates it for the builder's benefit - and it is stated here rather than papered over,
     * because a reader deciding whether to trust this invariant needs to know its exact edge.
     */
    public void setBookCoverHash(String bookCoverHash) {
        this.bookCoverHash = bookCoverHash;
        if (bookCoverHash != null) {
            this.coverProbedAt = null;
        }
    }

    /**
     * Holds the {@link #setBookCoverHash(String)} invariant across the builder, which would
     * otherwise assign both fields directly and let {@code builder().bookCoverHash(h)
     * .coverProbedAt(t).build()} produce the contradictory state the setter exists to prevent.
     * Lombok fills in the rest of the builder around these two methods.
     * <p>
     * Both setters are patched, not just the hash one, because the invariant has to hold whichever
     * order the caller calls them in: patching {@code bookCoverHash} alone still leaves a later
     * {@code coverProbedAt} free to reinstate the marker. The rule mirrors the entity's - a hash
     * always wins over a marker - so the two cannot be combined in either direction.
     * <p>
     * Only test fixtures build books with a cover hash today, so this is not fixing a live defect.
     * It is closing the route by which one arrives: the previous javadoc argued the builder was
     * "harmless because a book being built is new and has no marker yet", which is exactly the
     * reasoning-about-callers that pushed this invariant into the setter in the first place.
     */
    public static class BookEntityBuilder {

        public BookEntityBuilder bookCoverHash(String bookCoverHash) {
            this.bookCoverHash = bookCoverHash;
            if (bookCoverHash != null) {
                this.coverProbedAt = null;
            }
            return this;
        }

        public BookEntityBuilder coverProbedAt(Instant coverProbedAt) {
            this.coverProbedAt = this.bookCoverHash != null ? null : coverProbedAt;
            return this;
        }
    }

    @Column(name = "deleted")
    @Builder.Default
    private Boolean deleted = Boolean.FALSE;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @BatchSize(size = 20)
    @ManyToMany
    @JoinTable(
            name = "book_shelf_mapping",
            joinColumns = @JoinColumn(name = "book_id"),
            inverseJoinColumns = @JoinColumn(name = "shelf_id")
    )
    @Builder.Default
    private Set<ShelfEntity> shelves = new HashSet<>();

    @Basic(fetch = FetchType.LAZY)
    @LazyGroup("recommendations")
    @Convert(converter = BookRecommendationIdsListConverter.class)
    @Column(name = "similar_books_json", columnDefinition = "TEXT")
    private Set<BookRecommendationLite> similarBooksJson;

    @BatchSize(size = 20)
    @OneToMany(mappedBy = "book", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    @Builder.Default
    private List<BookFileEntity> bookFiles = new ArrayList<>();

    @BatchSize(size = 20)
    @OneToMany(mappedBy = "book", fetch = FetchType.LAZY)
    @Builder.Default
    private List<UserBookProgressEntity> userBookProgress = new ArrayList<>();

    public Path getFullFilePath() {
        BookFileEntity primaryBookFile = getPrimaryBookFile();
        if (primaryBookFile == null || libraryPath == null || libraryPath.getPath() == null || primaryBookFile.getFileSubPath() == null || primaryBookFile.getFileName() == null) {
            return null;
        }

        return Paths.get(libraryPath.getPath(), primaryBookFile.getFileSubPath(), primaryBookFile.getFileName());
    }

    public BookFileEntity getPrimaryBookFile() {
        if (bookFiles == null) {
            bookFiles = new ArrayList<>();
        }
        if (bookFiles.isEmpty()) {
            return null;
        }
        if (library != null && library.getFormatPriority() != null && !library.getFormatPriority().isEmpty()) {
            for (BookFileType format : library.getFormatPriority()) {
                var match = bookFiles.stream()
                        .filter(bf -> bf.isBookFormat() && bf.getBookType() == format)
                        .findFirst();
                if (match.isPresent()) {
                    return match.get();
                }
            }
        }
        return bookFiles.getFirst();
    }

    public boolean hasFiles() {
        return bookFiles != null && !bookFiles.isEmpty();
    }

    public void syncHasFiles() {
        this.hasFiles = hasFiles();
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !Hibernate.getClass(this).equals(Hibernate.getClass(o))) return false;
        BookEntity that = (BookEntity) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
