package org.booklore.service.metadata.extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.ComicMetadata;
import org.booklore.service.ArchiveService;
import org.springframework.stereotype.Component;
import org.booklore.util.SecureXmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

@Slf4j
@Component
public class CbxMetadataExtractor implements FileMetadataExtractor {

    private static final Pattern LEADING_ZEROS_PATTERN = Pattern.compile("^0+");
    private static final Pattern COMMA_SEMICOLON_PATTERN = Pattern.compile("[,;]");
    private static final Pattern BOOKLORE_NOTE_PATTERN = Pattern.compile("\\[BookLore:([^\\]]+)\\]\\s*(.*)");
    private static final Pattern WEB_SPLIT_PATTERN = Pattern.compile("[,;\\s]+");

    // URL Patterns
    private static final Pattern GOODREADS_URL_PATTERN = Pattern.compile("goodreads\\.com/book/show/(\\d+)(?:-[\\w-]+)?");
    private static final Pattern AMAZON_URL_PATTERN = Pattern.compile("amazon\\.com/dp/([A-Z0-9]{10})");
    private static final Pattern COMICVINE_URL_PATTERN = Pattern.compile("comicvine\\.gamespot\\.com/(?:issue|volume)/(?:[^/]+/)?(\\d+-\\d+)");
    private static final Pattern COMICVINE_SITE_URL_PATTERN = Pattern.compile("comicvine\\.gamespot\\.com/(?:[^/]+/)?(40(?:00|50)-\\d+)/?");
    private static final Pattern HARDCOVER_URL_PATTERN = Pattern.compile("hardcover\\.app/books/([\\w-]+)");
    private static final Pattern BOOKLORE_TAG_PATTERN = Pattern.compile("\\[BookLore:[^\\]]+\\][^\\n]*(\n|$)");
    private static final Pattern ISBN13_PATTERN = Pattern.compile("\\d{13}");
    private static final Pattern ISBN_CLEANER_PATTERN = Pattern.compile("[- ]");

    private final ArchiveService archiveService;

    public CbxMetadataExtractor(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @Override
    public BookMetadata extractMetadata(File file) {
        return extractMetadata(file.toPath());
    }

    public BookMetadata extractMetadata(Path path) {
        String baseName = FilenameUtils.getBaseName(path.toString());

        try (InputStream is = findComicInfoEntryInputStream(path)) {
            if (is != null) {
                Document document = buildSecureDocument(is);
                return mapDocumentToMetadata(document, baseName);
            } else {
                log.warn("No metadata existed in CBR");
            }
        } catch (Exception e) {
            log.warn("Failed to extract metadata from CBR", e);
        }

        return BookMetadata.builder().title(baseName).build();
    }

    private Document buildSecureDocument(InputStream is) throws ParserConfigurationException, SAXException, IOException {
        return SecureXmlUtils.createSecureDocumentBuilder(true).parse(is);
    }

    /**
     * Maps XML document to BookMetadata by extracting all relevant ComicInfo fields.
     * Handles missing or invalid fields gracefully by using fallback values or null.
     *
     * @param document      The parsed XML document containing ComicInfo data
     * @param fallbackTitle Title to use if Title element is missing or blank
     * @return BookMetadata object populated with extracted values
     */
    private BookMetadata mapDocumentToMetadata(
            Document document,
            String fallbackTitle
    ) {
        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder();

        applyCoreFields(document, builder, fallbackTitle);
        applyGtin(document, builder);
        applyBookCollections(document, builder);

        // Extract comic-specific metadata
        ComicMetadata.ComicMetadataBuilder comicBuilder = ComicMetadata.builder();
        if (applyComicMetadata(document, builder, comicBuilder)) {
            builder.comicMetadata(comicBuilder.build());
        }
        return builder.build();
    }

    private void applyCoreFields(Document document, BookMetadata.BookMetadataBuilder builder, String fallbackTitle) {
        String title = getTextContent(document, "Title");
        builder.title(title == null || title.isBlank() ? fallbackTitle : title);

        builder.description(
                coalesce(
                        getTextContent(document, "Summary"),
                        getTextContent(document, "Description")
                )
        );
        builder.publisher(getTextContent(document, "Publisher"));

        builder.seriesName(getTextContent(document, "Series"));
        builder.seriesNumber(parseFloat(getTextContent(document, "Number")));
        builder.seriesTotal(parseInteger(getTextContent(document, "Count")));
        builder.publishedDate(
                parseDate(
                        getTextContent(document, "Year"),
                        getTextContent(document, "Month"),
                        getTextContent(document, "Day")
                )
        );
        builder.pageCount(
                parseInteger(
                        coalesce(
                                getTextContent(document, "PageCount"),
                                getTextContent(document, "Pages")
                        )
                )
        );
        builder.language(getTextContent(document, "LanguageISO"));
    }

    private void applyGtin(Document document, BookMetadata.BookMetadataBuilder builder) {
        // GTIN is the standard ComicInfo field for ISBN (EAN/UPC)
        // Validate it's a 13-digit number (ISBN-13/EAN-13)
        String gtin = getTextContent(document, "GTIN");
        if (gtin == null || gtin.isBlank()) {
            return;
        }
        String normalized = ISBN_CLEANER_PATTERN.matcher(gtin).replaceAll("");
        if (ISBN13_PATTERN.matcher(normalized).matches()) {
            builder.isbn13(normalized);
        } else {
            log.debug("Invalid GTIN format (expected 13 digits): {}", gtin);
        }
    }

    private void applyBookCollections(Document document, BookMetadata.BookMetadataBuilder builder) {
        List<String> authors = new ArrayList<>(splitValues(getTextContent(document, "Writer")));
        if (!authors.isEmpty()) {
            builder.authors(authors);
        }

        Set<String> categories = splitValues(getTextContent(document, "Genre"));
        if (!categories.isEmpty()) {
            builder.categories(categories);
        }

        Set<String> tags = splitValues(getTextContent(document, "Tags"));
        if (!tags.isEmpty()) {
            builder.tags(tags);
        }
    }

    private boolean applyComicMetadata(
            Document document,
            BookMetadata.BookMetadataBuilder builder,
            ComicMetadata.ComicMetadataBuilder comicBuilder
    ) {
        boolean hasComicFields = applyComicIssueFields(document, comicBuilder);
        hasComicFields |= applyComicCreators(document, comicBuilder);
        hasComicFields |= applyComicPublicationFields(document, comicBuilder);
        hasComicFields |= applyComicEntities(document, comicBuilder);
        hasComicFields |= applyComicWebAndNotes(document, builder, comicBuilder);
        return hasComicFields;
    }

    private boolean applyComicIssueFields(Document document, ComicMetadata.ComicMetadataBuilder comicBuilder) {
        boolean hasComicFields = false;

        String issueNumber = getTextContent(document, "Number");
        if (issueNumber != null && !issueNumber.isBlank()) {
            comicBuilder.issueNumber(issueNumber);
            hasComicFields = true;
        }

        String volume = getTextContent(document, "Volume");
        if (volume != null && !volume.isBlank()) {
            comicBuilder.volumeName(getTextContent(document, "Series"));
            comicBuilder.volumeNumber(parseInteger(volume));
            hasComicFields = true;
        }

        String storyArc = getTextContent(document, "StoryArc");
        if (storyArc != null && !storyArc.isBlank()) {
            comicBuilder.storyArc(storyArc);
            comicBuilder.storyArcNumber(parseInteger(getTextContent(document, "StoryArcNumber")));
            hasComicFields = true;
        }

        String alternateSeries = getTextContent(document, "AlternateSeries");
        if (alternateSeries != null && !alternateSeries.isBlank()) {
            comicBuilder.alternateSeries(alternateSeries);
            comicBuilder.alternateIssue(getTextContent(document, "AlternateNumber"));
            hasComicFields = true;
        }

        return hasComicFields;
    }

    private boolean applyComicCreators(Document document, ComicMetadata.ComicMetadataBuilder comicBuilder) {
        boolean hasComicFields = false;

        Set<String> pencillers = splitValues(getTextContent(document, "Penciller"));
        if (!pencillers.isEmpty()) {
            comicBuilder.pencillers(pencillers);
            hasComicFields = true;
        }

        Set<String> inkers = splitValues(getTextContent(document, "Inker"));
        if (!inkers.isEmpty()) {
            comicBuilder.inkers(inkers);
            hasComicFields = true;
        }

        Set<String> colorists = splitValues(getTextContent(document, "Colorist"));
        if (!colorists.isEmpty()) {
            comicBuilder.colorists(colorists);
            hasComicFields = true;
        }

        Set<String> letterers = splitValues(getTextContent(document, "Letterer"));
        if (!letterers.isEmpty()) {
            comicBuilder.letterers(letterers);
            hasComicFields = true;
        }

        Set<String> coverArtists = splitValues(getTextContent(document, "CoverArtist"));
        if (!coverArtists.isEmpty()) {
            comicBuilder.coverArtists(coverArtists);
            hasComicFields = true;
        }

        Set<String> editors = splitValues(getTextContent(document, "Editor"));
        if (!editors.isEmpty()) {
            comicBuilder.editors(editors);
            hasComicFields = true;
        }

        return hasComicFields;
    }

    private boolean applyComicPublicationFields(Document document, ComicMetadata.ComicMetadataBuilder comicBuilder) {
        boolean hasComicFields = false;

        String imprint = getTextContent(document, "Imprint");
        if (imprint != null && !imprint.isBlank()) {
            comicBuilder.imprint(imprint);
            hasComicFields = true;
        }

        String format = getTextContent(document, "Format");
        if (format != null && !format.isBlank()) {
            comicBuilder.format(format);
            hasComicFields = true;
        }

        String blackAndWhite = getTextContent(document, "BlackAndWhite");
        if ("yes".equalsIgnoreCase(blackAndWhite) || "true".equalsIgnoreCase(blackAndWhite)) {
            comicBuilder.blackAndWhite(Boolean.TRUE);
            hasComicFields = true;
        }

        if (applyMangaField(document, comicBuilder)) {
            hasComicFields = true;
        }

        return hasComicFields;
    }

    private boolean applyMangaField(Document document, ComicMetadata.ComicMetadataBuilder comicBuilder) {
        String manga = getTextContent(document, "Manga");
        if (manga == null || manga.isBlank()) {
            return false;
        }
        boolean isManga = "yes".equalsIgnoreCase(manga) || "true".equalsIgnoreCase(manga) || "yesandrighttoleft".equalsIgnoreCase(manga);
        comicBuilder.manga(isManga);
        if ("yesandrighttoleft".equalsIgnoreCase(manga)) {
            comicBuilder.readingDirection("rtl");
        } else {
            comicBuilder.readingDirection("ltr");
        }
        return true;
    }

    private boolean applyComicEntities(Document document, ComicMetadata.ComicMetadataBuilder comicBuilder) {
        boolean hasComicFields = false;

        Set<String> characters = splitValues(getTextContent(document, "Characters"));
        if (!characters.isEmpty()) {
            comicBuilder.characters(characters);
            hasComicFields = true;
        }

        Set<String> teams = splitValues(getTextContent(document, "Teams"));
        if (!teams.isEmpty()) {
            comicBuilder.teams(teams);
            hasComicFields = true;
        }

        Set<String> locations = splitValues(getTextContent(document, "Locations"));
        if (!locations.isEmpty()) {
            comicBuilder.locations(locations);
            hasComicFields = true;
        }

        return hasComicFields;
    }

    private boolean applyComicWebAndNotes(
            Document document,
            BookMetadata.BookMetadataBuilder builder,
            ComicMetadata.ComicMetadataBuilder comicBuilder
    ) {
        boolean hasComicFields = false;

        String web = getTextContent(document, "Web");
        if (web != null && !web.isBlank()) {
            comicBuilder.webLink(web);
            hasComicFields = true;
            // Also parse the web field for IDs (goodreads, comicvine, etc.)
            parseWebField(web, builder);
        }

        String notes = getTextContent(document, "Notes");
        if (notes != null && !notes.isBlank()) {
            comicBuilder.notes(notes);
            hasComicFields = true;
            parseNotes(notes, builder);
            applyNotesDescriptionFallback(document, builder, notes);
        }

        return hasComicFields;
    }

    private void applyNotesDescriptionFallback(Document document, BookMetadata.BookMetadataBuilder builder, String notes) {
        // Store whether we already have a description from Summary/Description XML elements
        String existingDescription = coalesce(
                getTextContent(document, "Summary"),
                getTextContent(document, "Description")
        );
        boolean hasDescription = existingDescription != null && !existingDescription.isBlank();

        // If description is missing, use cleaned notes (removing BookLore tags)
        if (!hasDescription) {
            String cleanedNotes = BOOKLORE_TAG_PATTERN.matcher(notes).replaceAll("").trim();
            if (!cleanedNotes.isEmpty()) {
                builder.description(cleanedNotes);
            }
        }
    }

    private void parseWebField(String web, BookMetadata.BookMetadataBuilder builder) {
        String[] urls = WEB_SPLIT_PATTERN.split(web);
        for (String url : urls) {
            if (url.isBlank()) {
                continue;
            }
            applyWebUrlToBuilder(url.trim(), builder);
        }
    }

    private void applyWebUrlToBuilder(String url, BookMetadata.BookMetadataBuilder builder) {
        Matcher grMatcher = GOODREADS_URL_PATTERN.matcher(url);
        if (grMatcher.find()) {
            builder.goodreadsId(grMatcher.group(1));
            return;
        }

        Matcher azMatcher = AMAZON_URL_PATTERN.matcher(url);
        if (azMatcher.find()) {
            builder.asin(azMatcher.group(1));
            return;
        }

        Matcher cvMatcher = COMICVINE_URL_PATTERN.matcher(url);
        if (cvMatcher.find()) {
            builder.comicvineId(cvMatcher.group(1));
            return;
        }

        Matcher cvSiteMatcher = COMICVINE_SITE_URL_PATTERN.matcher(url);
        if (cvSiteMatcher.find()) {
            builder.comicvineId(cvSiteMatcher.group(1));
            return;
        }

        Matcher hcMatcher = HARDCOVER_URL_PATTERN.matcher(url);
        if (hcMatcher.find()) {
            builder.hardcoverId(hcMatcher.group(1));
        }
    }

    // S6916: the "when" guard clause syntax cannot attach to constant case labels (only to type
    // patterns), so the single-if bodies below cannot be rewritten as guarded case labels - Sonar
    // flags this for string switches too (known false positive, see java:S6916 discussions).
    @SuppressWarnings("java:S6916")
    private void parseNotes(String notes, BookMetadata.BookMetadataBuilder builder) {
        Matcher matcher = BOOKLORE_NOTE_PATTERN.matcher(notes);
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = matcher.group(2).trim();

            switch (key) {
                case "Moods" -> {
                    if (!value.isEmpty()) builder.moods(splitValues(value));
                }
                case "Tags" -> {
                    if (!value.isEmpty()) {
                        Set<String> tags = splitValues(value);
                        BookMetadata current = builder.build();
                        if (current.getTags() != null) tags.addAll(current.getTags());
                        builder.tags(tags);
                    }
                }
                case "Subtitle" -> builder.subtitle(value);
                case "ISBN13" -> builder.isbn13(value);
                case "ISBN10" -> builder.isbn10(value);
                case "AmazonRating" -> safeParseDouble(value, builder::amazonRating);
                case "GoodreadsRating" -> safeParseDouble(value, builder::goodreadsRating);
                case "HardcoverRating" -> safeParseDouble(value, builder::hardcoverRating);
                case "LubimyczytacRating" -> safeParseDouble(value, builder::lubimyczytacRating);
                case "RanobedbRating" -> safeParseDouble(value, builder::ranobedbRating);
                case "HardcoverBookId" -> builder.hardcoverBookId(value);
                case "HardcoverId" -> builder.hardcoverId(value);
                case "LubimyczytacId" -> builder.lubimyczytacId(value);
                case "RanobedbId" -> builder.ranobedbId(value);
                case "GoogleId" -> builder.googleId(value);
                case "GoodreadsId" -> builder.goodreadsId(value);
                case "ASIN" -> builder.asin(value);
                case "ComicvineId" -> builder.comicvineId(value);
                default -> log.debug("Unrecognized BookLore note key: {}", key);
            }
        }
    }

    private void safeParseDouble(String value, DoubleConsumer consumer) {
        try {
            consumer.accept(Double.parseDouble(value));
        } catch (NumberFormatException _) {
            log.debug("Failed to parse double from value: {}", value);
        }
    }

    /**
     * Extracts and trims text content from the first element with the given tag name.
     *
     * @param document The XML document to search
     * @param tag      The tag name to find
     * @return Trimmed text content of the first matching element, or null if not found
     */
    private String getTextContent(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent().trim();
    }

    private String coalesce(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }

    /**
     * Splits a delimited string into a set of trimmed values.
     * Supports both comma (,) and semicolon (;) as delimiters.
     * Empty values after trimming are excluded from the result.
     *
     * @param value The delimited string to split
     * @return Set of trimmed non-empty values, or empty set if input is null
     */
    private Set<String> splitValues(String value) {
        if (value == null) {
            return new HashSet<>();
        }
        return Arrays.stream(COMMA_SEMICOLON_PATTERN.split(value))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private Integer parseInteger(String value) {
        try {
            return (value == null || value.isBlank())
                    ? null
                    : Integer.parseInt(value.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private Float parseFloat(String value) {
        try {
            return (value == null || value.isBlank())
                    ? null
                    : Float.parseFloat(value.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * Parses year, month, and day strings into a LocalDate.
     * Defaults missing month/day to 1. Returns null if year is missing or invalid.
     *
     * @param year  Year string (required)
     * @param month Month string (optional, defaults to 1)
     * @param day   Day string (optional, defaults to 1)
     * @return LocalDate object, or null if parsing fails or year is null
     */
    private LocalDate parseDate(String year, String month, String day) {
        Integer y = parseInteger(year);
        Integer m = parseInteger(month);
        Integer d = parseInteger(day);
        if (y == null) {
            return null;
        }
        if (m == null) {
            m = 1;
        }
        if (d == null) {
            d = 1;
        }
        try {
            return LocalDate.of(y, m, d);
        } catch (Exception _) {
            return null;
        }
    }

    @Override
    public byte[] extractCover(File file) {
        return extractCover(file.toPath());
    }

    /**
     * {@inheritDoc}
     * <p>
     * The only honest "no cover" a comic archive has is an archive with no page images in it at all
     * - a CBZ holding nothing but a readme. That is the single {@code null} below, and it is reached
     * only after the archive has been opened and its entries listed.
     * <p>
     * Everything else that ends without a cover is a failure to read, not proof of absence, and the
     * three used to be one swallowed {@code null}: an archive that would not open (an unsupported
     * RAR5, say) came back the same as an empty one, because {@link #listComicImageEntries} turned
     * the failure into an empty stream. So did an archive whose pages are all there but unreadable,
     * because {@link #canDecode} filtered them away silently. Both now leave as a
     * {@link CoverExtractionException}: a comic whose pages we cannot decode plainly has pages, and
     * telling the user it has no cover would be a lie.
     */
    @SuppressWarnings("java:S1168") // null (not empty array) means "proven no cover"; BookCoverGenerator/BookdropMetadataService branch on == null
    public byte[] extractCover(Path path) {
        List<String> imageEntries = listComicImageEntries(path);

        List<String> candidates = Stream.concat(
                        coverEntryNamesFromComicInfo(path, imageEntries),
                        imageEntries.stream().sorted(
                                (a, b) -> Boolean.compare(likelyCoverName(baseName(b)), likelyCoverName(baseName(a))))
                )
                .distinct()
                .toList();

        boolean sawUnreadableEntry = false;
        boolean sawUndecodableEntry = false;
        for (String entry : candidates) {
            byte[] bytes = readArchiveEntryBytes(path, entry);
            if (bytes == null) {
                sawUnreadableEntry = true;
                continue;
            }
            if (canDecode(bytes)) {
                return bytes;
            }
            // Not fatal on its own: a comic can lead with a broken page and still have good ones,
            // which is the whole reason this loop keeps going rather than taking the first entry.
            sawUndecodableEntry = true;
        }

        if (imageEntries.isEmpty()) {
            // Archive opened and listed, and there is not one page image in it. Proven absence.
            return null;
        }
        throw new CoverExtractionException(
                "Comic archive %s holds %d image entr%s but none could be turned into a cover (unreadable=%s, undecodable=%s)"
                        .formatted(path.getFileName(), imageEntries.size(), imageEntries.size() == 1 ? "y" : "ies",
                                sawUnreadableEntry, sawUndecodableEntry));
    }

    private boolean canDecode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            BufferedImage img = ImageIO.read(bais);
            return img != null;
        } catch (IOException _) {
            return false;
        }
    }

    private Stream<String> coverEntryNamesFromComicInfo(Path cbxPath, List<String> entryNames) {
        Set<String> possibleCoverImages = new LinkedHashSet<>();

        try (InputStream is = findComicInfoEntryInputStream(cbxPath)) {
            if (is == null) {
                return Stream.empty();
            }

            Document document = buildSecureDocument(is);

            NodeList pages = document.getElementsByTagName("Page");
            for (int i = 0; i < pages.getLength(); i++) {
                collectCoverImageFromPage(pages.item(i), entryNames, possibleCoverImages);
            }

        } catch (Exception e) {
            log.warn("Failed to read ComicInfo.xml from archive {}: {}", cbxPath, e.getMessage());
        }

        return possibleCoverImages.stream();
    }

    private void collectCoverImageFromPage(Node node, List<String> entryNames, Set<String> possibleCoverImages) {
        try {
            if (!(node instanceof Element page)) {
                return;
            }

            if (!"FrontCover".equalsIgnoreCase(page.getAttribute("Type"))) {
                return;
            }

            // The `ImageFile` is an entry name to read.
            String imageFile = page.getAttribute("ImageFile");
            if (imageFile != null && !imageFile.isBlank()) {
                possibleCoverImages.add(imageFile.trim());
            }

            // The `Image` attribute is an index of the pages in the CBZ to read.
            String image = page.getAttribute("Image");
            if (image != null && !image.isBlank()) {
                int index = Integer.parseInt(image.trim());
                if (entryNames.size() > index) {
                    possibleCoverImages.add(entryNames.get(index));
                } else if (index > 0 && entryNames.size() > index - 1) {
                    // It's possible there's an off-by-one error in some cases.
                    possibleCoverImages.add(entryNames.get(index - 1));
                }
            }
        } catch (Exception _) {
            // Do nothing
        }
    }

    /**
     * The archive's page images, in natural order.
     * <p>
     * This is the read that decides whether a "no cover" answer is available at all, so it must not
     * swallow: it used to return an empty stream when the archive could not be opened, which made an
     * unopenable archive indistinguishable from one containing no images. An empty list from here
     * now means one thing only - the archive opened and has no page images.
     */
    private List<String> listComicImageEntries(Path cbxPath) {
        try {
            return archiveService.streamEntryNames(cbxPath)
                    .filter(this::isImageEntry)
                    .sorted(this::naturalCompare)
                    .toList();
        } catch (Exception e) {
            throw new CoverExtractionException("Failed to list entries of comic archive: " + cbxPath.getFileName(), e);
        }
    }

    private boolean isImageEntry(String name) {
        if (!isContentEntry(name)) return false;
        String lower = name.toLowerCase();
        return (
                lower.endsWith(".jpg") ||
                        lower.endsWith(".jpeg") ||
                        lower.endsWith(".png") ||
                        lower.endsWith(".gif") ||
                        lower.endsWith(".bmp") ||
                        lower.endsWith(".webp")
        );
    }

    private boolean isContentEntry(String name) {
        if (name == null) return false;
        String norm = name.replace('\\', '/');
        if (norm.startsWith("__MACOSX/") || norm.contains("/__MACOSX/")) return false;
        String base = baseName(norm);
        if (base.startsWith(".")) return false;
        return !".ds_store".equalsIgnoreCase(base);
    }

    private String findComicInfoEntry(Path cbxPath) {
        try {
            return archiveService.streamEntryNames(cbxPath)
                    .filter(CbxMetadataExtractor::isComicInfoName)
                    .findFirst()
                    .orElse(null);
        } catch (Exception _) {
            log.warn("Could not find comic info entry for archive {}", cbxPath.getFileName());
            return null;
        }
    }

    private InputStream findComicInfoEntryInputStream(Path cbxPath) {
        String comicInfoEntry = findComicInfoEntry(cbxPath);

        if (comicInfoEntry == null) {
            // If we can't find a comic info entry, give up.
            return null;
        }

        byte[] xmlBytes = readArchiveEntryBytes(cbxPath, comicInfoEntry);

        if (xmlBytes == null) {
            return null;
        }

        return new ByteArrayInputStream(xmlBytes);
    }

    // S1168: null (not empty array) distinguishes "could not read this entry" from a zero-byte
    // entry; callers here (extractCover's unreadable/undecodable bookkeeping, findComicInfoEntryInputStream)
    // branch on == null.
    @SuppressWarnings("java:S1168")
    private byte[] readArchiveEntryBytes(Path cbxPath, String entryName) {
        try {
            return archiveService.getEntryBytes(cbxPath, entryName);
        } catch (Exception e) {
            log.warn("Failed to read archive {} entry bytes for {}", cbxPath.getFileName(), entryName, e);
        }

        return null;
    }

    private String baseName(String path) {
        if (path == null) return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private boolean likelyCoverName(String base) {
        if (base == null) return false;
        String n = base.toLowerCase();
        return n.startsWith("cover") || "folder".equals(n) || n.startsWith("front");
    }

    private int naturalCompare(String a, String b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        return compareNaturally(a.toLowerCase(), b.toLowerCase());
    }

    private int compareNaturally(String s1, String s2) {
        int i = 0;
        int j = 0;
        int n1 = s1.length();
        int n2 = s2.length();
        while (i < n1 && j < n2) {
            char c1 = s1.charAt(i);
            char c2 = s2.charAt(j);
            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                DigitRun run = compareDigitRuns(s1, s2, i, j, n1, n2);
                if (run.comparison() != 0) return run.comparison();
                i = run.nextI();
                j = run.nextJ();
            } else if (c1 != c2) {
                return Character.compare(c1, c2);
            } else {
                i++;
                j++;
            }
        }
        return Integer.compare(n1 - i, n2 - j);
    }

    private DigitRun compareDigitRuns(String s1, String s2, int i, int j, int n1, int n2) {
        int i1 = i;
        while (i1 < n1 && Character.isDigit(s1.charAt(i1))) i1++;
        int j1 = j;
        while (j1 < n2 && Character.isDigit(s2.charAt(j1))) j1++;
        String num1 = LEADING_ZEROS_PATTERN.matcher(s1.substring(i, i1)).replaceFirst("");
        String num2 = LEADING_ZEROS_PATTERN.matcher(s2.substring(j, j1)).replaceFirst("");
        int cmp = Integer.compare(num1.isEmpty() ? 0 : Integer.parseInt(num1), num2.isEmpty() ? 0 : Integer.parseInt(num2));
        return new DigitRun(cmp, i1, j1);
    }

    private record DigitRun(int comparison, int nextI, int nextJ) {
    }

    private static boolean isComicInfoName(String name) {
        if (name == null) return false;
        String n = name.replace('\\', '/');
        if (n.endsWith("/")) return false;
        String lower = n.toLowerCase();
        return "comicinfo.xml".equals(lower) || lower.endsWith("/comicinfo.xml");
    }
}
