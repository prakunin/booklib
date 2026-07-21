package org.booklore.service.metadata.extractor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.grimmory.epub4j.archive.EpubContainer;
import org.grimmory.epub4j.archive.EpubContainers;
import org.grimmory.epub4j.domain.Book;
import org.grimmory.epub4j.domain.MediaType;
import org.grimmory.epub4j.domain.MediaTypes;
import org.grimmory.epub4j.epub.CoverDetector;
import org.grimmory.epub4j.epub.CoverDetector.CoverDetectionResult;
import org.grimmory.epub4j.epub.EpubReader;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.booklore.model.dto.BookMetadata;
import org.booklore.service.metadata.BookLoreMetadata;
import org.booklore.util.SecureXmlUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

@Slf4j
@Component
public class EpubMetadataExtractor implements FileMetadataExtractor {

    private static final Pattern YEAR_ONLY_PATTERN = Pattern.compile("^\\d{4}$");
    private static final String OPF_NS = "http://www.idpf.org/2007/opf";

    // List of all media types that epub4j has so we can lazy load them.
    // Note that we have to add in null to handle files without extentions like mimetype.
    private static final List<MediaType> MEDIA_TYPES = new ArrayList<>();
    private static final Pattern ISBN_SEPARATOR_PATTERN = Pattern.compile("[- ]");

    private static final Set<Integer> VALID_AGE_RATINGS = Set.of(0, 6, 10, 13, 16, 18, 21);
    private static final String COVER_KEYWORD = "cover";
    private static final String CONTENT_ATTR = "content";

    private final ObjectMapper objectMapper;

    static {
        MEDIA_TYPES.addAll(Arrays.asList(MediaTypes.mediaTypes));
        MEDIA_TYPES.add(null);
    }

    public EpubMetadataExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static final Map<String, BiConsumer<BookMetadata.BookMetadataBuilder, String>> CALIBRE_IDENTIFIER_PREFIXES = Map.of(
            "amazon", BookMetadata.BookMetadataBuilder::asin,
            "asin", BookMetadata.BookMetadataBuilder::asin,
            "mobi-asin", BookMetadata.BookMetadataBuilder::asin,
            "goodreads", BookMetadata.BookMetadataBuilder::goodreadsId,
            "google", BookMetadata.BookMetadataBuilder::googleId,
            "hardcover", BookMetadata.BookMetadataBuilder::hardcoverId,
            "hardcover_book", BookMetadata.BookMetadataBuilder::hardcoverBookId,
            "comicvine", BookMetadata.BookMetadataBuilder::comicvineId,
            "lubimyczytac", BookMetadata.BookMetadataBuilder::lubimyczytacId,
            "ranobedb", BookMetadata.BookMetadataBuilder::ranobedbId);

    private static final Map<String, BiConsumer<BookMetadata.BookMetadataBuilder, String>> CALIBRE_FIELD_MAPPINGS = Map.ofEntries(
            Map.entry("#subtitle", BookMetadata.BookMetadataBuilder::subtitle),
            Map.entry("#pagecount", (builder, value) -> safeParseInt(value, builder::pageCount)),
            Map.entry("#series_total", (builder, value) -> safeParseInt(value, builder::seriesTotal)),
            Map.entry("#amazon_rating", (builder, value) -> safeParseDouble(value, builder::amazonRating)),
            Map.entry("#amazon_review_count", (builder, value) -> safeParseInt(value, builder::amazonReviewCount)),
            Map.entry("#goodreads_rating", (builder, value) -> safeParseDouble(value, builder::goodreadsRating)),
            Map.entry("#goodreads_review_count", (builder, value) -> safeParseInt(value, builder::goodreadsReviewCount)),
            Map.entry("#hardcover_rating", (builder, value) -> safeParseDouble(value, builder::hardcoverRating)),
            Map.entry("#hardcover_review_count", (builder, value) -> safeParseInt(value, builder::hardcoverReviewCount)),
            Map.entry("#lubimyczytac_rating", (builder, value) -> safeParseDouble(value, builder::lubimyczytacRating)),
            Map.entry("#ranobedb_rating", (builder, value) -> safeParseDouble(value, builder::ranobedbRating)),
            Map.entry("#age_rating", (builder, value) -> safeParseInt(value, v -> {
                if (VALID_AGE_RATINGS.contains(v)) builder.ageRating(v);
            })),
            Map.entry("#content_rating", (builder, value) -> {
                String normalized = value.trim().toUpperCase();
                if (Set.of("EVERYONE", "TEEN", "MATURE", "ADULT", "EXPLICIT").contains(normalized)) {
                    builder.contentRating(normalized);
                }
            }));

    /**
     * {@inheritDoc}
     * <p>
     * Two readers run in sequence and they are not equal partners. epub4j is tried first for its
     * cover detection, and its failure is deliberately swallowed: it is an optimisation, and the
     * container scan below is the fallback that exists precisely for the EPUBs it cannot handle.
     * The container scan is the authority. If it opens the archive, finds the OPF and walks it
     * through to the end without finding a cover, that is a proven absence and returns {@code null};
     * if it throws - the file is missing, the zip is corrupt, the OPF will not parse - then nothing
     * read this EPUB and we are in no position to say it has no cover, so it leaves as a
     * {@link CoverExtractionException}. Both used to return the same bare {@code null}, which is why
     * regenerating the cover of a corrupt EPUB told the user it had "no embedded cover image".
     */
    @Override
    @SuppressWarnings("java:S1168") // null (not empty array) means "proven no cover"; BookCoverGenerator/BookdropMetadataService/EpubProcessor branch on == null
    public byte[] extractCover(File epubFile) {
        // Primary: use epub4j's CoverDetector with native lazy loading
        byte[] fastPathCover = extractCoverViaEpub4j(epubFile);
        if (fastPathCover.length > 0) {
            return fastPathCover;
        }

        // Last resort: scan container for cover-like images
        return scanContainerForCover(epubFile);
    }

    private byte[] extractCoverViaEpub4j(File epubFile) {
        try {
            Book book = new EpubReader().readEpubLazy(epubFile.toPath(), "UTF-8");
            Optional<CoverDetectionResult> detection = CoverDetector.detectCoverImageWithMethod(book);
            if (detection.isPresent()) {
                CoverDetectionResult result = detection.get();
                log.debug("Cover detected for {} via {}: {}",
                        epubFile.getName(), result.method(), result.resource().getHref());
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                result.resource().writeTo(baos);
                byte[] data = baos.toByteArray();
                if (data.length > 0) {
                    return data;
                }
            }
        } catch (Exception e) {
            // Not a verdict: epub4j is the fast path, and the container scan below is the fallback
            // that exists for exactly the files it chokes on. Only that scan gets to conclude.
            log.debug("epub4j cover detection failed for {}: {}", epubFile.getName(), e.getMessage());
        }
        // Purely an internal "fast path found nothing usable, try the fallback" signal - unlike
        // extractCover's null, this carries no external "proven absence" contract, so an empty
        // array is a safe, equivalent sentinel.
        return new byte[0];
    }

    @SuppressWarnings("java:S1168") // null (not empty array) means "proven no cover"; BookCoverGenerator/BookdropMetadataService/EpubProcessor branch on == null
    private byte[] scanContainerForCover(File epubFile) {
        try (EpubContainer container = EpubContainers.open(epubFile.toPath())) {
            // NOTE: this will moved to org.grimmory.epub4j in the near future
            // most of the parsing done here, can be safely replaced with methods already existing in epub4j
            String opfName = findOpfPath(container);
            Document opf = parseXmlFromContainer(container, opfName);

            NodeList items = opf.getElementsByTagName("item");

            byte[] byProperty = findCoverByManifestProperty(container, opfName, items);
            if (byProperty != null) {
                return byProperty;
            }

            byte[] byName = findCoverByManifestName(container, opfName, items);
            if (byName != null) {
                return byName;
            }

            byte[] byFileScan = findCoverByFileScan(container);
            if (byFileScan != null) {
                return byFileScan;
            }
        } catch (Exception e) {
            throw new CoverExtractionException("Failed to extract cover from EPUB: " + epubFile.getName(), e);
        }

        // The container opened, the OPF parsed and every route through it came up empty: proven.
        return null;
    }

    // Try OPF manifest for cover-image property
    @SuppressWarnings("java:S1168") // null distinguishes "not found here" from a zero-length cover resource
    private byte[] findCoverByManifestProperty(EpubContainer container, String opfName, NodeList items) throws IOException {
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String properties = item.getAttribute("properties");
            if (properties != null && properties.contains("cover-image")) {
                String href = URLDecoder.decode(item.getAttribute("href"), StandardCharsets.UTF_8);
                String fullPath = resolvePath(opfName, href);
                if (container.exists(fullPath)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
                    container.streamTo(fullPath, baos);
                    return baos.toByteArray();
                }
            }
        }
        return null;
    }

    // Search manifest for cover-looking items by id/href
    @SuppressWarnings("java:S1168") // null distinguishes "not found here" from a zero-length cover resource
    private byte[] findCoverByManifestName(EpubContainer container, String opfName, NodeList items) throws IOException {
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String id = item.getAttribute("id");
            String href = item.getAttribute("href");
            String mediaType = item.getAttribute("media-type");
            if (mediaType != null && mediaType.startsWith("image/")
                    && ((id != null && id.toLowerCase().contains(COVER_KEYWORD)) ||
                        (href != null && href.toLowerCase().contains(COVER_KEYWORD)))) {
                String decodedHref = URLDecoder.decode(href, StandardCharsets.UTF_8);
                String fullPath = resolvePath(opfName, decodedHref);
                if (container.exists(fullPath)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
                    container.streamTo(fullPath, baos);
                    return baos.toByteArray();
                }
            }
        }
        return null;
    }

    // Scan all files for cover-named images
    @SuppressWarnings("java:S1168") // null distinguishes "not found here" from a zero-length cover resource
    private byte[] findCoverByFileScan(EpubContainer container) throws IOException {
        for (String name : container.listAllFiles()) {
            String lower = name.toLowerCase();
            if (lower.contains(COVER_KEYWORD) && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".png") || lower.endsWith(".webp"))) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
                container.streamTo(name, baos);
                return baos.toByteArray();
            }
        }
        return null;
    }

    @Override
    // S6916: the "when" guard clause syntax cannot attach to constant case labels (only to type
    // patterns), so the single-if bodies in the switches below cannot be rewritten as guarded case
    // labels - Sonar flags this for string switches too (known false positive, see java:S6916 discussions).
    @SuppressWarnings("java:S6916")
    public BookMetadata extractMetadata(File epubFile) {
        try (EpubContainer container = EpubContainers.open(epubFile.toPath())) {
            String opfPath = findOpfPath(container);
            Document doc = parseXmlFromContainer(container, opfPath);

            Element metadata = (Element) doc.getElementsByTagNameNS("*", "metadata").item(0);
            if (metadata == null) return null;

            BookMetadata.BookMetadataBuilder builderMeta = BookMetadata.builder();
            Set<String> categories = new HashSet<>();
            Set<String> moods = new HashSet<>();
            Set<String> tags = new HashSet<>();

            boolean seriesFound = false;
            boolean seriesIndexFound = false;

            NodeList children = metadata.getChildNodes();

            Map<String, String> creatorsById = new HashMap<>();
            Map<String, List<String>> creatorRolesById = new HashMap<>();
            Map<String, List<String>> creatorsByRole = new HashMap<>();
            creatorsByRole.put("aut", new ArrayList<>());

            Map<String, String> titlesById = new HashMap<>();
            Map<String, String> titleTypeById = new HashMap<>();
            boolean hasTitle = false;
            MetaParseState metaParseState = new MetaParseState(builderMeta, moods, tags, titleTypeById, creatorRolesById);

            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element el)) continue;

                String tag = el.getLocalName();
                String text = el.getTextContent().trim();

                switch (tag) {
                    case "title" -> {
                        handleTitle(el, text, builderMeta, titlesById, hasTitle);
                        hasTitle = true;
                    }
                    case "meta" -> {
                        SeriesFlags flags = handleMeta(el, text, metaParseState,
                                new SeriesFlags(seriesFound, seriesIndexFound));
                        seriesFound = flags.seriesFound();
                        seriesIndexFound = flags.seriesIndexFound();
                    }
                    case "creator" -> handleCreator(el, text, creatorsById, creatorsByRole);
                    case "subject" -> categories.add(text);
                    case "description" -> builderMeta.description(text);
                    case "publisher" -> builderMeta.publisher(text);
                    case "language" -> builderMeta.language(text);
                    case "identifier" -> handleIdentifier(el, text, builderMeta);
                    case "date" -> {
                        LocalDate parsed = parseDate(text);
                        if (parsed != null) builderMeta.publishedDate(parsed);
                    }
                    default -> log.debug("Unhandled OPF metadata tag: {}", tag);
                }
            }

            applyTitleTypes(titlesById, titleTypeById, builderMeta);
            applyModifiedDateFallback(builderMeta, children);
            applyCreators(creatorsById, creatorRolesById, creatorsByRole, builderMeta);
            applyCollections(builderMeta, categories, moods, tags);

            return finalizeTitle(builderMeta, epubFile);
        } catch (Exception e) {
            log.error("Failed to read metadata from EPUB file {}: {}", epubFile.getName(), e.getMessage(), e);
            return null;
        }
    }

    private record SeriesFlags(boolean seriesFound, boolean seriesIndexFound) {
    }

    // Groups the parser state that is threaded through every "meta" element for the whole
    // document (as opposed to seriesFound/seriesIndexFound, which are per-iteration flags -
    // see SeriesFlags), so handleMeta doesn't need one param per accumulator.
    private record MetaParseState(BookMetadata.BookMetadataBuilder builderMeta, Set<String> moods, Set<String> tags,
                                  Map<String, String> titleTypeById, Map<String, List<String>> creatorRolesById) {
    }

    private void handleTitle(Element el, String text, BookMetadata.BookMetadataBuilder builderMeta,
                             Map<String, String> titlesById, boolean hasTitle) {
        String id = el.getAttribute("id");
        if (StringUtils.isNotBlank(id)) {
            titlesById.put(id, text);
        }

        if (!hasTitle) {
            builderMeta.title(text);
        }
    }

    private SeriesFlags handleMeta(Element el, String text, MetaParseState state, SeriesFlags flags) {
        String prop = el.getAttribute("property").trim();
        String name = el.getAttribute("name").trim();
        String refines = el.getAttribute("refines").trim();
        String content = el.hasAttribute(CONTENT_ATTR) ? el.getAttribute(CONTENT_ATTR).trim() : text;

        BookMetadata.BookMetadataBuilder builderMeta = state.builderMeta();
        handleRefinesAndLayout(prop, refines, content, builderMeta, state.titleTypeById(), state.creatorRolesById());
        SeriesFlags updatedFlags = updateSeries(prop, name, content, builderMeta, flags.seriesFound(), flags.seriesIndexFound());
        handlePageCountMeta(prop, name, content, builderMeta, state.moods(), state.tags());
        applyMetaKey(StringUtils.isNotBlank(prop) ? prop : name, content, builderMeta, state.moods(), state.tags());

        return updatedFlags;
    }

    private void handleRefinesAndLayout(String prop, String refines, String content,
                                        BookMetadata.BookMetadataBuilder builderMeta,
                                        Map<String, String> titleTypeById,
                                        Map<String, List<String>> creatorRolesById) {
        if ("title-type".equals(prop) && StringUtils.isNotBlank(refines)) {
            titleTypeById.put(refines.substring(1), content.toLowerCase());
        }

        if ("role".equals(prop) && StringUtils.isNotBlank(refines)) {
            creatorRolesById.computeIfAbsent(refines.substring(1), k -> new ArrayList<>()).add(content.toLowerCase());
        }

        if ("rendition:layout".equals(prop) && "pre-paginated".equals(content)) {
            builderMeta.isFixedLayout(true);
        }
    }

    private SeriesFlags updateSeries(String prop, String name, String content,
                                     BookMetadata.BookMetadataBuilder builderMeta,
                                     boolean seriesFound, boolean seriesIndexFound) {
        if (!seriesFound && ((BookLoreMetadata.NS_PREFIX + ":series").equals(prop) || "calibre:series".equals(name) || "belongs-to-collection".equals(prop))) {
            builderMeta.seriesName(content);
            seriesFound = true;
        }
        if (!seriesIndexFound && ((BookLoreMetadata.NS_PREFIX + ":series_index").equals(prop) || "calibre:series_index".equals(name) || "group-position".equals(prop))
                && trySetSeriesNumber(builderMeta, content)) {
            seriesIndexFound = true;
        }
        return new SeriesFlags(seriesFound, seriesIndexFound);
    }

    private void handlePageCountMeta(String prop, String name, String content,
                                     BookMetadata.BookMetadataBuilder builderMeta,
                                     Set<String> moods, Set<String> tags) {
        if ("calibre:pages".equals(name) || "pagecount".equals(name) || "schema:pagecount".equals(prop) || "media:pagecount".equals(prop) || (BookLoreMetadata.NS_PREFIX + ":page_count").equals(prop)) {
            safeParseInt(content, builderMeta::pageCount);
        } else if ("calibre:user_metadata:#pagecount".equals(name)) {
            parseUserMetadataPageCount(content, builderMeta);
        } else if ("calibre:user_metadata".equals(prop)) {
            parseUserMetadata(content, builderMeta, moods, tags);
        }
    }

    // S6916: the "when" guard clause syntax cannot attach to constant case labels (only to type
    // patterns), so the single-if bodies in the switch below cannot be rewritten as guarded case
    // labels - same known false positive as extractMetadata's switch above.
    @SuppressWarnings("java:S6916")
    private void applyMetaKey(String key, String content, BookMetadata.BookMetadataBuilder builderMeta,
                              Set<String> moods, Set<String> tags) {
        switch (key) {
            case BookLoreMetadata.NS_PREFIX + ":asin" -> builderMeta.asin(content);
            case BookLoreMetadata.NS_PREFIX + ":goodreads_id" -> builderMeta.goodreadsId(content);
            case BookLoreMetadata.NS_PREFIX + ":comicvine_id" -> builderMeta.comicvineId(content);
            case BookLoreMetadata.NS_PREFIX + ":ranobedb_id" -> builderMeta.ranobedbId(content);
            case BookLoreMetadata.NS_PREFIX + ":hardcover_id" -> builderMeta.hardcoverId(content);
            case BookLoreMetadata.NS_PREFIX + ":google_books_id" -> builderMeta.googleId(content);
            case BookLoreMetadata.NS_PREFIX + ":lubimyczytac_id" -> builderMeta.lubimyczytacId(content);
            case BookLoreMetadata.NS_PREFIX + ":page_count" ->
                    safeParseInt(content, builderMeta::pageCount);
            case BookLoreMetadata.NS_PREFIX + ":subtitle" -> builderMeta.subtitle(content);
            case BookLoreMetadata.NS_PREFIX + ":series_total" ->
                    safeParseInt(content, builderMeta::seriesTotal);
            case BookLoreMetadata.NS_PREFIX + ":rating" -> {
                // rating handled elsewhere; ignore here
            }
            case BookLoreMetadata.NS_PREFIX + ":amazon_rating" ->
                    safeParseDouble(content, builderMeta::amazonRating);
            case BookLoreMetadata.NS_PREFIX + ":amazon_review_count" ->
                    safeParseInt(content, builderMeta::amazonReviewCount);
            case BookLoreMetadata.NS_PREFIX + ":goodreads_rating" ->
                    safeParseDouble(content, builderMeta::goodreadsRating);
            case BookLoreMetadata.NS_PREFIX + ":goodreads_review_count" ->
                    safeParseInt(content, builderMeta::goodreadsReviewCount);
            case BookLoreMetadata.NS_PREFIX + ":hardcover_rating" ->
                    safeParseDouble(content, builderMeta::hardcoverRating);
            case BookLoreMetadata.NS_PREFIX + ":hardcover_review_count" ->
                    safeParseInt(content, builderMeta::hardcoverReviewCount);
            case BookLoreMetadata.NS_PREFIX + ":lubimyczytac_rating" ->
                    safeParseDouble(content, builderMeta::lubimyczytacRating);
            case BookLoreMetadata.NS_PREFIX + ":ranobedb_rating" ->
                    safeParseDouble(content, builderMeta::ranobedbRating);
            case BookLoreMetadata.NS_PREFIX + ":age_rating" -> safeParseInt(content, v -> {
                if (VALID_AGE_RATINGS.contains(v)) builderMeta.ageRating(v);
            });
            case BookLoreMetadata.NS_PREFIX + ":content_rating" -> builderMeta.contentRating(content);
            case BookLoreMetadata.NS_PREFIX + ":moods" -> {
                if (StringUtils.isNotBlank(content)) {
                    extractSetField(content, moods);
                }
            }
            case BookLoreMetadata.NS_PREFIX + ":tags" -> {
                if (StringUtils.isNotBlank(content)) {
                    extractSetField(content, tags);
                }
            }
            default -> log.debug("Unhandled meta key: {}", key);
        }
    }

    private void handleCreator(Element el, String text, Map<String, String> creatorsById,
                               Map<String, List<String>> creatorsByRole) {
        String role = el.getAttributeNS(OPF_NS, "role");
        if (StringUtils.isNotBlank(role)) {
            creatorsByRole.computeIfAbsent(role, k -> new ArrayList<>()).add(text);
        } else {
            String id = el.getAttribute("id");
            if (StringUtils.isNotBlank(id)) {
                creatorsById.put(id, text);
            } else {
                creatorsByRole.get("aut").add(text);
            }
        }
    }

    private void handleIdentifier(Element el, String text, BookMetadata.BookMetadataBuilder builderMeta) {
        String scheme = el.getAttributeNS(OPF_NS, "scheme").toUpperCase();
        String textLower = text.toLowerCase();

        // Parse URN format: urn:scheme:value
        String value = text;
        String urnScheme = null;
        if (textLower.startsWith("urn:")) {
            String[] parts = text.split(":", 3);
            if (parts.length >= 3) {
                urnScheme = parts[1].toUpperCase();
                value = parts[2];
            }
        } else if (textLower.startsWith("isbn:")) {
            value = text.substring(5);
            urnScheme = "ISBN";
        }

        // Use URN scheme if opf:scheme is not present
        if (scheme.isEmpty() && urnScheme != null) {
            scheme = urnScheme;
        }

        if (!scheme.isEmpty()) {
            applyIdentifierScheme(scheme, value, builderMeta);
        } else {
            applyCalibrePrefixIdentifier(text, builderMeta);
        }
    }

    private void applyIdentifierScheme(String scheme, String value, BookMetadata.BookMetadataBuilder builderMeta) {
        switch (scheme) {
            case "ISBN", "ISBN10", "ISBN13" -> {
                String cleanValue = ISBN_SEPARATOR_PATTERN.matcher(value).replaceAll("");
                if (cleanValue.length() == 13) builderMeta.isbn13(value);
                else if (cleanValue.length() == 10) builderMeta.isbn10(value);
            }
            case "GOODREADS" -> builderMeta.goodreadsId(value);
            case "COMICVINE" -> builderMeta.comicvineId(value);
            case "RANOBEDB" -> builderMeta.ranobedbId(value);
            case "GOOGLE" -> builderMeta.googleId(value);
            case "AMAZON" -> builderMeta.asin(value);
            case "HARDCOVER" -> builderMeta.hardcoverId(value);
            case "HARDCOVERBOOK", "HARDCOVER_BOOK_ID" -> builderMeta.hardcoverBookId(value);
            case "LUBIMYCZYTAC" -> builderMeta.lubimyczytacId(value);
            default -> log.debug("Unhandled identifier scheme: {}", scheme);
        }
    }

    private void applyCalibrePrefixIdentifier(String text, BookMetadata.BookMetadataBuilder builderMeta) {
        // Handle Calibre's prefix:value format (e.g., amazon:B09XXX, goodreads:123)
        int colonIdx = text.indexOf(':');
        if (colonIdx > 0) {
            String prefix = text.substring(0, colonIdx).toLowerCase();
            String val = text.substring(colonIdx + 1).trim();
            if (!"calibre".equals(prefix) && !"uuid".equals(prefix)) {
                BiConsumer<BookMetadata.BookMetadataBuilder, String> setter = CALIBRE_IDENTIFIER_PREFIXES.get(prefix);
                if (setter != null) {
                    setter.accept(builderMeta, val);
                }
            }
        }
    }

    private void applyTitleTypes(Map<String, String> titlesById, Map<String, String> titleTypeById,
                                 BookMetadata.BookMetadataBuilder builderMeta) {
        for (Map.Entry<String, String> entry : titlesById.entrySet()) {
            String type = titleTypeById.get(entry.getKey());
            if ("main".equals(type)) builderMeta.title(entry.getValue());
            else if ("subtitle".equals(type)) builderMeta.subtitle(entry.getValue());
        }
    }

    private void applyModifiedDateFallback(BookMetadata.BookMetadataBuilder builderMeta, NodeList children) {
        if (builderMeta.build().getPublishedDate() == null) {
            LocalDate modifiedDate = findDctermsModifiedDate(children);
            if (modifiedDate != null) {
                builderMeta.publishedDate(modifiedDate);
            }
        }
    }

    private void applyCreators(Map<String, String> creatorsById, Map<String, List<String>> creatorRolesById,
                               Map<String, List<String>> creatorsByRole, BookMetadata.BookMetadataBuilder builderMeta) {
        for (Map.Entry<String, String> entry : creatorsById.entrySet()) {
            List<String> roles = creatorRolesById.getOrDefault(entry.getKey(), List.of("aut"));
            for (String role : roles) {
                creatorsByRole.computeIfAbsent(role, k -> new ArrayList<>()).add(entry.getValue());
            }
        }

        builderMeta.authors(creatorsByRole.get("aut"));
    }

    private void applyCollections(BookMetadata.BookMetadataBuilder builderMeta, Set<String> categories,
                                  Set<String> moods, Set<String> tags) {
        if (!moods.isEmpty()) builderMeta.moods(moods);
        if (!tags.isEmpty()) builderMeta.tags(tags);

        // Remove moods and tags from categories to ensure strict separation
        categories.removeAll(moods);
        categories.removeAll(tags);

        builderMeta.categories(categories);
    }

    private BookMetadata finalizeTitle(BookMetadata.BookMetadataBuilder builderMeta, File epubFile) {
        BookMetadata extractedMetadata = builderMeta.build();

        if (StringUtils.isBlank(extractedMetadata.getTitle())) {
            builderMeta.title(FilenameUtils.getBaseName(epubFile.getName()));
            extractedMetadata = builderMeta.build();
        }

        return extractedMetadata;
    }

    private boolean trySetSeriesNumber(BookMetadata.BookMetadataBuilder builderMeta, String content) {
        try {
            builderMeta.seriesNumber(Float.parseFloat(content));
            return true;
        } catch (NumberFormatException _) {
            // ignore unparseable series index
            return false;
        }
    }

    private void parseUserMetadataPageCount(String content, BookMetadata.BookMetadataBuilder builderMeta) {
        try {
            JsonNode jsonRoot = objectMapper.readTree(content);
            JsonNode valueNode = jsonRoot.get("#value#");
            if (valueNode != null && !valueNode.isNull()) {
                safeParseInt(valueNode.asString(), builderMeta::pageCount);
            }
        } catch (Exception e) {
            log.debug("Failed to parse calibre:user_metadata:#pagecount: {}", e.getMessage());
        }
    }

    private void parseUserMetadata(String content, BookMetadata.BookMetadataBuilder builderMeta, Set<String> moods, Set<String> tags) {
        try {
            extractCalibreUserMetadata(objectMapper.readTree(content), builderMeta, moods, tags);
        } catch (Exception e) {
            log.debug("Failed to parse calibre:user_metadata: {}", e.getMessage());
        }
    }

    private LocalDate findDctermsModifiedDate(NodeList children) {
        for (int i = 0; i < children.getLength(); i++) {
            Element el = asMetaElement(children.item(i));
            if (el != null) {
                String prop = el.getAttribute("property").trim().toLowerCase();
                if ("dcterms:modified".equals(prop)) {
                    String content = el.hasAttribute(CONTENT_ATTR) ? el.getAttribute(CONTENT_ATTR).trim() : el.getTextContent().trim();
                    LocalDate parsed = parseDate(content);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }
        return null;
    }

    private Element asMetaElement(Node node) {
        if (!(node instanceof Element el) || !"meta".equals(el.getLocalName())) {
            return null;
        }
        return el;
    }

    private static void safeParseInt(String value, IntConsumer setter) {
        try {
            setter.accept(Integer.parseInt(value));
        } catch (NumberFormatException _) {
            // ignore unparseable value
        }
    }

    private static void safeParseDouble(String value, DoubleConsumer setter) {
        try {
            setter.accept(Double.parseDouble(value));
        } catch (NumberFormatException _) {
            // ignore unparseable value
        }
    }
    
    private void extractSetField(String value, Set<String> targetSet) {
        if (StringUtils.isNotBlank(value)) {
            targetSet.addAll(parseJsonArrayOrCsv(value));
        }
    }

    private void extractCalibreUserMetadata(JsonNode userMetadata, BookMetadata.BookMetadataBuilder builder,
                                             Set<String> moodsSet, Set<String> tagsSet) {
        if (!(userMetadata instanceof ObjectNode objectNode)) {
            return;
        }
        for (Map.Entry<String, JsonNode> field : objectNode.properties()) {
            applyCalibreUserMetadataField(field, builder, moodsSet, tagsSet);
        }
    }

    private void applyCalibreUserMetadataField(Map.Entry<String, JsonNode> field, BookMetadata.BookMetadataBuilder builder,
                                                Set<String> moodsSet, Set<String> tagsSet) {
        String fieldName = field.getKey();
        try {
            JsonNode fieldObj = field.getValue();
            if (fieldObj == null || !fieldObj.isObject()) {
                return;
            }

            JsonNode valueNode = fieldObj.get("#value#");
            if (valueNode == null || valueNode.isNull()) {
                return;
            }

            if ("#moods".equals(fieldName) || "#extra_tags".equals(fieldName)) {
                applyMoodsOrTagsField(fieldName, valueNode, moodsSet, tagsSet);
            } else {
                applyMappedCalibreField(fieldName, valueNode, builder);
            }
        } catch (Exception e) {
            log.debug("Failed to extract Calibre field '{}': {}", fieldName, e.getMessage());
        }
    }

    private void applyMoodsOrTagsField(String fieldName, JsonNode valueNode, Set<String> moodsSet, Set<String> tagsSet) {
        String value = valueNode.isArray() ? valueNode.toString() : valueNode.asString().trim();
        if (value.isEmpty() || "null".equals(value)) {
            return;
        }
        extractSetField(value, "#moods".equals(fieldName) ? moodsSet : tagsSet);
    }

    private void applyMappedCalibreField(String fieldName, JsonNode valueNode, BookMetadata.BookMetadataBuilder builder) {
        String value = valueNode.asString().trim();
        if (value.isEmpty() || "null".equals(value)) {
            return;
        }
        BiConsumer<BookMetadata.BookMetadataBuilder, String> mapper = CALIBRE_FIELD_MAPPINGS.get(fieldName);
        if (mapper != null) {
            mapper.accept(builder, value);
        }
    }

    /**
     * Parses a string that may be either a JSON array (e.g., ["item1", "item2"]) or a CSV (item1, item2).
     * Returns a Set of parsed values.
     */
    private Set<String> parseJsonArrayOrCsv(String content) {
        if (StringUtils.isBlank(content)) {
            return new HashSet<>();
        }
        
        content = content.trim();
        
        // Check if it looks like a JSON array
        if (content.startsWith("[") && content.endsWith("]")) {
            // Remove brackets
            String inner = content.substring(1, content.length() - 1).trim();
            if (inner.isEmpty()) {
                return new HashSet<>();
            }

            // Split by comma and parse each quoted item
            return Arrays.stream(inner.split(","))
                    .map(String::trim)
                    .map(s -> {
                        // Remove surrounding quotes if present
                        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                            return s.substring(1, s.length() - 1);
                        }
                        return s;
                    })
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toSet());
        }

        // Fallback to CSV parsing
        return Arrays.stream(content.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
    }

    private LocalDate parseDate(String value) {
        if (StringUtils.isBlank(value)) return null;

        value = value.trim();

        // Check for year-only format first (e.g., "2024") - common in EPUB metadata
        if (YEAR_ONLY_PATTERN.matcher(value).matches()) {
            int year = Integer.parseInt(value);
            if (year >= 1 && year <= 9999) {
                return LocalDate.of(year, Month.JANUARY, 1);
            }
        }

        try {
            return LocalDate.parse(value);
        } catch (Exception _) {
            // not an ISO local date; try the next format
        }

        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (Exception _) {
            // not an offset date-time; try the next format
        }

        // Try parsing first 10 characters for ISO date format with extra content
        if (value.length() >= 10) {
            try {
                return LocalDate.parse(value.substring(0, 10));
            } catch (Exception _) {
                // leading 10 chars are not an ISO date; fall through
            }
        }

        log.warn("Failed to parse date from string: {}", value);
        return null;
    }

    private String findOpfPath(EpubContainer container) throws IOException, ParserConfigurationException, SAXException {
        String containerXmlPath = "META-INF/container.xml";
        if (!container.exists(containerXmlPath)) {
            return "OEBPS/content.opf";
        }

        Document containerDoc = parseXmlFromContainer(container, containerXmlPath);
        NodeList rootfiles = containerDoc.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:container", "rootfile");
        if (rootfiles.getLength() == 0) {
            throw new IOException("No <rootfile> found in container.xml");
        }

        // EPUB spec §3.5.1: first rootfile is the default rendition
        String opfPath = ((Element) rootfiles.item(0)).getAttribute("full-path");
        if (StringUtils.isBlank(opfPath)) {
            throw new IOException("Empty full-path in container.xml");
        }

        return URLDecoder.decode(opfPath, StandardCharsets.UTF_8);
    }

    private Document parseXmlFromContainer(EpubContainer container, String path) throws IOException, ParserConfigurationException, SAXException {
        if (!container.exists(path)) {
            throw new IOException("File not found: " + path);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        container.streamTo(path, baos);

        return SecureXmlUtils.createSecureDocumentBuilder(true).parse(new ByteArrayInputStream(baos.toByteArray()));
    }

    private String resolvePath(String opfPath, String href) {
        if (href == null || href.isEmpty()) return null;

        // If href is absolute within the zip (starts with /), return it without leading /
        if (href.startsWith("/")) return href.substring(1);

        int lastSlash = opfPath.lastIndexOf('/');
        String basePath = (lastSlash == -1) ? "" : opfPath.substring(0, lastSlash + 1);

        String combined = basePath + href;

        // Normalize path components to handle ".." and "."
        LinkedList<String> parts = new LinkedList<>();
        for (String part : combined.split("/")) {
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.removeLast();
            } else if (!".".equals(part) && !part.isEmpty()) {
                parts.add(part);
            }
        }

        return String.join("/", parts);
    }

}
