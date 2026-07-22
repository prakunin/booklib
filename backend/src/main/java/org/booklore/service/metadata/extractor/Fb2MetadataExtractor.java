package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
public class Fb2MetadataExtractor implements FileMetadataExtractor {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{4}");
    private static final Pattern ISBN_PATTERN = Pattern.compile("\\d{9}[\\dXx]");
    private static final Pattern KEYWORD_SEPARATOR_PATTERN = Pattern.compile("[,;]");
    private static final Pattern ISBN_CLEANER_PATTERN = Pattern.compile("[^0-9Xx]");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final String TITLE_INFO_ELEMENT = "title-info";
    private static final String BINARY_ELEMENT = "binary";

    /**
     * {@inheritDoc}
     * <p>
     * Every {@code return null} below is reached only after the document has parsed, so each one
     * means "this FB2 was read and has no cover". Everything else - the file missing, a truncated
     * document that fails to parse, corrupt base64 in a binary element - leaves through the catch as
     * a {@link CoverExtractionException}. That split used to be a single swallowed {@code null},
     * which {@code BookCoverService}'s lazy probe recorded as a permanent "no cover" verdict.
     */
    @Override
    @SuppressWarnings("java:S1168") // null (not empty array) means "proven no cover"; BookCoverGenerator/BookdropMetadataService branch on == null
    public byte[] extractCover(File file) {
        try {
            CoverCandidate candidate = findCoverCandidate(file);
            if (candidate == null) {
                // Parsed all the way through and found no cover binary: a genuine, permanent miss.
                return null;
            }
            return decodeCoverBinary(file, candidate.binaryId());
        } catch (CoverExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new CoverExtractionException("Failed to extract cover from FB2: " + file.getName(), e);
        }
    }

    private CoverCandidate findCoverCandidate(File file) throws IOException, XMLStreamException {
        try (InputStream inputStream = getInputStream(file)) {
            XMLStreamReader reader = createXmlStreamReader(inputStream);
            CoverScan scan = new CoverScan();
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        handleCoverStartElement(reader, scan);
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (scan.coverpageDepth > 0) {
                            scan.coverpageDepth--;
                        }
                        if (scan.titleInfoDepth > 0) {
                            scan.titleInfoDepth--;
                        }
                    }
                }
            } finally {
                reader.close();
            }

            String binaryId = StringUtils.defaultIfBlank(scan.coverBinaryId, scan.referencedImageId);
            return StringUtils.isBlank(binaryId) ? null : new CoverCandidate(binaryId);
        }
    }

    private void handleCoverStartElement(XMLStreamReader reader, CoverScan scan) {
        if (scan.titleInfoDepth > 0) {
            scan.titleInfoDepth++;
        }
        if (scan.coverpageDepth > 0) {
            scan.coverpageDepth++;
        }

        String localName = reader.getLocalName();
        if (TITLE_INFO_ELEMENT.equals(localName)) {
            scan.titleInfoDepth = 1;
        } else if (scan.titleInfoDepth > 0 && "coverpage".equals(localName)) {
            scan.coverpageDepth = 1;
        } else if (scan.coverpageDepth > 0 && "image".equals(localName) && scan.referencedImageId == null) {
            readReferencedImageId(reader, scan);
        } else if (BINARY_ELEMENT.equals(localName) && scan.coverBinaryId == null) {
            readCoverBinaryId(reader, scan);
        }
    }

    private void readReferencedImageId(XMLStreamReader reader, CoverScan scan) {
        String href = getHrefAttribute(reader);
        if (href != null && href.startsWith("#")) {
            scan.referencedImageId = href.substring(1);
        }
    }

    private void readCoverBinaryId(XMLStreamReader reader, CoverScan scan) {
        String id = reader.getAttributeValue(null, "id");
        String contentType = reader.getAttributeValue(null, "content-type");
        if (Strings.CI.contains(id, "cover")
                && Strings.CI.startsWith(contentType, "image/")) {
            scan.coverBinaryId = id;
        }
    }

    private static final class CoverScan {
        private String coverBinaryId;
        private String referencedImageId;
        private int titleInfoDepth;
        private int coverpageDepth;
    }

    @SuppressWarnings("java:S1168") // return value flows straight through to extractCover's null-means-no-cover contract
    private byte[] decodeCoverBinary(File file, String binaryId) throws IOException, XMLStreamException {
        try (InputStream inputStream = getInputStream(file)) {
            XMLStreamReader reader = createXmlStreamReader(inputStream);
            BinaryScan scan = new BinaryScan();
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        handleBinaryStart(reader, scan, binaryId);
                    } else if (isTextEvent(event) && scan.base64 != null) {
                        scan.base64.append(reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT && scan.base64 != null) {
                        byte[] decoded = handleBinaryEnd(reader, scan);
                        if (decoded != null) {
                            return decoded;
                        }
                    }
                }
            } finally {
                reader.close();
            }
            return null;
        }
    }

    private void handleBinaryStart(XMLStreamReader reader, BinaryScan scan, String binaryId) {
        if (scan.base64 != null) {
            scan.targetDepth++;
        } else if (BINARY_ELEMENT.equals(reader.getLocalName())
                && binaryId.equals(reader.getAttributeValue(null, "id"))) {
            scan.base64 = new StringBuilder();
            scan.targetDepth = 1;
        }
    }

    // null means "not yet at the target binary's end tag, keep scanning" - a genuinely different
    // state from a successful decode of empty base64 content, which legitimately yields byte[0]
    // from Base64.getMimeDecoder().decode(""). decodeCoverBinary's `!= null` check depends on
    // telling those two apart, so null cannot be replaced with an empty array here.
    @SuppressWarnings("java:S1168")
    private byte[] handleBinaryEnd(XMLStreamReader reader, BinaryScan scan) {
        if (scan.targetDepth == 1 && BINARY_ELEMENT.equals(reader.getLocalName())) {
            return Base64.getMimeDecoder().decode(scan.base64.toString().trim());
        }
        scan.targetDepth--;
        return null;
    }

    private static boolean isTextEvent(int event) {
        return event == XMLStreamConstants.CHARACTERS
                || event == XMLStreamConstants.CDATA
                || event == XMLStreamConstants.SPACE;
    }

    private static final class BinaryScan {
        private StringBuilder base64;
        private int targetDepth;
    }

    @Override
    public BookMetadata extractMetadata(File file) {
        try (InputStream inputStream = getInputStream(file)) {
            return extractMetadata(inputStream, file.getName());
        } catch (Exception e) {
            log.warn("Failed to extract metadata from FB2: {}", file.getName(), e);
            return null;
        }
    }

    /**
     * Extracts metadata from an FB2 stream without taking ownership of the stream.
     * This is used for books that live inside INPX ZIP archives, where extracting every
     * entry to a temporary file would add avoidable disk IO.
     */
    public BookMetadata extractMetadata(InputStream inputStream, String sourceName) {
        try {
            BookMetadata.BookMetadataBuilder metadataBuilder = BookMetadata.builder();
            List<String> authors = new ArrayList<>();
            Set<String> categories = new HashSet<>();
            List<String> bodyParagraphs = new ArrayList<>();
            boolean inBody = false;
            boolean afterDescription = false;

            XMLStreamReader reader = createXmlStreamReader(inputStream);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT && "description".equals(reader.getLocalName())) {
                        extractDescription(reader, metadataBuilder, authors, categories);
                        afterDescription = true;
                    } else if (event == XMLStreamConstants.START_ELEMENT && "body".equals(reader.getLocalName())) {
                        inBody = true;
                    } else if (event == XMLStreamConstants.START_ELEMENT && afterDescription && !inBody) {
                        // Do not scan arbitrary trailing payloads (especially large or
                        // truncated <binary> elements). Only a document body is useful
                        // for the fallback title-page recovery.
                        break;
                    } else if (event == XMLStreamConstants.START_ELEMENT && inBody
                            && "p".equals(reader.getLocalName()) && bodyParagraphs.size() < 40) {
                        String paragraph = readElementText(reader);
                        if (StringUtils.isNotBlank(paragraph)) {
                            bodyParagraphs.add(paragraph.trim());
                        }
                    }
                }
            } finally {
                reader.close();
            }

            metadataBuilder.authors(authors);
            metadataBuilder.categories(categories);
            BookMetadata metadata = metadataBuilder.build();
            applyBodyMetadataFallback(metadata, bodyParagraphs);
            return metadata;
        } catch (Exception e) {
            log.warn("Failed to extract metadata from FB2: {}", sourceName, e);
            return null;
        }
    }

    /**
     * A number of older FB2 files have broken title-info values (for example a
     * conversion filename and a converter name), while the actual title page in
     * the body is correct. Recover only when the structured values are clearly
     * placeholders; normal, well-formed FB2 metadata remains untouched.
     */
    private void applyBodyMetadataFallback(BookMetadata metadata, List<String> paragraphs) {
        if (paragraphs.isEmpty()) {
            return;
        }

        if (isPlaceholderTitle(metadata.getTitle())) {
            paragraphs.stream()
                    .map(this::quotedText)
                    .filter(StringUtils::isNotBlank)
                    .findFirst()
                    .ifPresent(metadata::setTitle);
        }

        if (hasPlaceholderAuthor(metadata.getAuthors())) {
            paragraphs.stream()
                    .filter(this::looksLikePersonName)
                    .findFirst()
                    .ifPresent(name -> metadata.setAuthors(List.of(name)));
        }

        if (StringUtils.isBlank(metadata.getSubtitle())) {
            for (int i = 0; i + 1 < paragraphs.size(); i++) {
                if (paragraphs.get(i).toLowerCase(Locale.ROOT).contains("оригинальное название")) {
                    paragraphs.subList(i + 1, paragraphs.size()).stream()
                            .filter(this::looksLikeOriginalTitle)
                            .findFirst()
                            .map(this::normalizeParagraph)
                            .ifPresent(metadata::setSubtitle);
                    break;
                }
            }
            if (StringUtils.isBlank(metadata.getSubtitle())) {
                paragraphs.stream()
                        .filter(this::looksLikeOriginalTitle)
                        .findFirst()
                        .map(this::normalizeParagraph)
                        .ifPresent(metadata::setSubtitle);
            }
        }
    }

    private boolean isPlaceholderTitle(String title) {
        return StringUtils.isBlank(title)
                || title.matches("(?i)^_?\\d+\\.(docx|fb2|epub|txt)$")
                || title.toLowerCase(Locale.ROOT).contains("convertstandard.com");
    }

    private boolean hasPlaceholderAuthor(List<String> authors) {
        return authors == null || authors.isEmpty()
                || authors.stream().anyMatch(author -> author.toLowerCase(Locale.ROOT).contains("convertstandard.com"));
    }

    private boolean looksLikePersonName(String value) {
        return value.length() <= 100
                && value.matches("^[\\p{L}][\\p{L} .'-]*$")
                && value.chars().filter(Character::isLetter).count() >= 4;
    }

    private boolean looksLikeOriginalTitle(String value) {
        long latinLetters = value.chars()
                .filter(ch -> (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'))
                .count();
        return latinLetters >= 3 && (value.matches(".*\\d{4}.*") || value.contains("«") || value.contains("\""));
    }

    private String normalizeParagraph(String value) {
        return value.replaceFirst("(?i)^.*?оригинальное\\s+название\\s*:\\s*", "")
                .replaceAll("\\s+", " ").trim();
    }

    private String quotedText(String value) {
        String text = value.trim();
        if ((text.startsWith("«") && text.endsWith("»"))
                || (text.startsWith("\"") && text.endsWith("\""))) {
            return text.substring(1, text.length() - 1).trim();
        }
        return null;
    }

    private void extractDescription(XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder,
                                    List<String> authors, Set<String> categories) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case TITLE_INFO_ELEMENT -> extractTitleInfo(reader, builder, authors, categories);
                    case "publish-info" -> extractPublishInfo(reader, builder);
                    case "document-info" -> extractDocumentInfo(reader);
                    default -> {
                        // ignore other elements
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "description".equals(reader.getLocalName())) {
                return;
            }
        }
    }

    private void extractTitleInfo(XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder,
                                  List<String> authors, Set<String> categories) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "genre" -> addCategory(categories, readElementText(reader));
                    case "author" -> addAuthor(authors, extractPersonName(reader));
                    case "book-title" -> builder.title(readElementText(reader));
                    case "annotation" -> applyAnnotation(reader, builder);
                    case "keywords" -> addKeywords(categories, readElementText(reader));
                    case "date" -> applyDate(reader, builder);
                    case "lang" -> builder.language(readElementText(reader));
                    case "sequence" -> {
                        extractSequence(reader, builder);
                        skipElement(reader);
                    }
                    default -> {
                        // ignore other elements
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && TITLE_INFO_ELEMENT.equals(reader.getLocalName())) {
                return;
            }
        }
    }

    private void applyAnnotation(XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder) throws XMLStreamException {
        String description = readElementText(reader);
        if (StringUtils.isNotBlank(description)) {
            builder.description(description);
        }
    }

    private void applyDate(XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder) throws XMLStreamException {
        String dateValue = reader.getAttributeValue(null, "value");
        if (StringUtils.isBlank(dateValue)) {
            dateValue = readElementText(reader);
        } else {
            skipElement(reader);
        }
        LocalDate publishedDate = parseDate(dateValue);
        if (publishedDate != null) {
            builder.publishedDate(publishedDate);
        }
    }

    private void extractPublishInfo(XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder)
            throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "publisher" -> builder.publisher(readElementText(reader));
                    case "year" -> extractPublicationYear(reader, builder);
                    case "isbn" -> extractIsbn(reader, builder);
                    default -> {
                        // ignore other elements
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "publish-info".equals(reader.getLocalName())) {
                return;
            }
        }
    }

    private void extractDocumentInfo(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && "id".equals(reader.getLocalName())) {
                log.debug("FB2 document ID: {}", readElementText(reader));
            } else if (event == XMLStreamConstants.END_ELEMENT && "document-info".equals(reader.getLocalName())) {
                return;
            }
        }
    }

    private void addCategory(Set<String> categories, String category) {
        if (StringUtils.isNotBlank(category)) {
            categories.add(category.trim());
        }
    }

    private void addKeywords(Set<String> categories, String keywordsText) {
        if (StringUtils.isBlank(keywordsText)) {
            return;
        }
        for (String keyword : KEYWORD_SEPARATOR_PATTERN.split(keywordsText)) {
            addCategory(categories, keyword);
        }
    }

    private void addAuthor(List<String> authors, String authorName) {
        if (StringUtils.isNotBlank(authorName)) {
            authors.add(authorName);
        }
    }

    private String extractPersonName(XMLStreamReader reader) throws XMLStreamException {
        return assembleName(readPersonNameParts(reader));
    }

    private PersonName readPersonNameParts(XMLStreamReader reader) throws XMLStreamException {
        String firstName = null;
        String middleName = null;
        String lastName = null;
        String nickname = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "first-name" -> firstName = readElementText(reader);
                    case "middle-name" -> middleName = readElementText(reader);
                    case "last-name" -> lastName = readElementText(reader);
                    case "nickname" -> nickname = readElementText(reader);
                    default -> {
                        // ignore other elements
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "author".equals(reader.getLocalName())) {
                break;
            }
        }

        return new PersonName(firstName, middleName, lastName, nickname);
    }

    private String assembleName(PersonName parts) {
        StringBuilder name = new StringBuilder(64);

        if (StringUtils.isNotBlank(parts.firstName())) {
            name.append(parts.firstName().trim());
        }
        if (StringUtils.isNotBlank(parts.middleName())) {
            appendWithSpace(name, parts.middleName().trim());
        }
        if (StringUtils.isNotBlank(parts.lastName())) {
            appendWithSpace(name, parts.lastName().trim());
        }

        if (name.isEmpty() && StringUtils.isNotBlank(parts.nickname())) {
            name.append(parts.nickname().trim());
        }

        return name.toString();
    }

    private void appendWithSpace(StringBuilder name, String value) {
        if (!name.isEmpty()) {
            name.append(" ");
        }
        name.append(value);
    }

    private record PersonName(String firstName, String middleName, String lastName, String nickname) {
    }

    private void extractSequence(XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder) {
        String seriesName = reader.getAttributeValue(null, "name");
        if (StringUtils.isNotBlank(seriesName)) {
            builder.seriesName(seriesName.trim());
        }
        String seriesNumber = reader.getAttributeValue(null, "number");
        if (StringUtils.isNotBlank(seriesNumber)) {
            try {
                builder.seriesNumber(Float.parseFloat(seriesNumber));
            } catch (NumberFormatException _) {
                log.debug("Failed to parse series number: {}", seriesNumber);
            }
        }
    }

    private void extractPublicationYear(XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder)
            throws XMLStreamException {
        String yearText = readElementText(reader);
        Matcher matcher = YEAR_PATTERN.matcher(yearText);
        if (matcher.find()) {
            try {
                int yearValue = Integer.parseInt(matcher.group());
                builder.publishedDate(LocalDate.of(yearValue, Month.JANUARY, 1));
            } catch (NumberFormatException _) {
                log.debug("Failed to parse year: {}", yearText);
            }
        }
    }

    private void extractIsbn(XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder)
            throws XMLStreamException {
        String isbnText = ISBN_CLEANER_PATTERN.matcher(readElementText(reader)).replaceAll("");
        if (isbnText.length() == 13) {
            builder.isbn13(isbnText);
        } else if (isbnText.length() == 10) {
            builder.isbn10(isbnText);
        } else if (ISBN_PATTERN.matcher(isbnText).find()) {
            Matcher matcher = ISBN_PATTERN.matcher(isbnText);
            if (matcher.find()) {
                builder.isbn10(matcher.group());
            }
        }
    }

    private String readElementText(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        int depth = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.CHARACTERS
                    || event == XMLStreamConstants.CDATA
                    || event == XMLStreamConstants.SPACE) {
                text.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (depth == 0) {
                    return text.toString().trim();
                }
                depth--;
            }
        }
        throw new XMLStreamException("Unexpected end of FB2 element");
    }

    private void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (depth == 0) {
                    return;
                }
                depth--;
            }
        }
    }

    private String getHrefAttribute(XMLStreamReader reader) {
        String href = reader.getAttributeValue("http://www.w3.org/1999/xlink", "href");
        if (href != null) {
            return href;
        }
        href = reader.getAttributeValue(null, "href");
        if (href != null) {
            return href;
        }
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if ("href".equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    private XMLStreamReader createXmlStreamReader(InputStream inputStream) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        setXmlProperty(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return factory.createXMLStreamReader(inputStream);
    }

    private void setXmlProperty(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException e) {
            log.debug("XMLInputFactory does not support property {}", property, e);
        }
    }

    private LocalDate parseDate(String dateString) {
        if (StringUtils.isBlank(dateString)) {
            return null;
        }

        try {
            // Try parsing ISO date format (YYYY-MM-DD)
            if (ISO_DATE_PATTERN.matcher(dateString).matches()) {
                return LocalDate.parse(dateString);
            }

            // Try extracting year only
            Matcher matcher = YEAR_PATTERN.matcher(dateString);
            if (matcher.find()) {
                int year = Integer.parseInt(matcher.group());
                return LocalDate.of(year, Month.JANUARY, 1);
            }
        } catch (Exception e) {
            log.debug("Failed to parse date: {}", dateString, e);
        }

        return null;
    }

    private InputStream getInputStream(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        if (file.getName().toLowerCase().endsWith(".gz")) {
            try {
                return new GZIPInputStream(fis);
            } catch (Exception e) {
                fis.close();
                throw e;
            }
        }
        return fis;
    }

    private record CoverCandidate(String binaryId) {
    }
}
