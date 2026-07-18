package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
public class Fb2MetadataExtractor implements FileMetadataExtractor {

    private static final String FB2_NAMESPACE = "http://www.gribuser.ru/xml/fictionbook/2.0";
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{4}");
    private static final Pattern ISBN_PATTERN = Pattern.compile("\\d{9}[\\dXx]");
    private static final Pattern KEYWORD_SEPARATOR_PATTERN = Pattern.compile("[,;]");
    private static final Pattern ISBN_CLEANER_PATTERN = Pattern.compile("[^0-9Xx]");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

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

    private CoverCandidate findCoverCandidate(File file) throws Exception {
        try (InputStream inputStream = getInputStream(file)) {
            XMLStreamReader reader = createXmlStreamReader(inputStream);
            String coverBinaryId = null;
            String referencedImageId = null;
            int titleInfoDepth = 0;
            int coverpageDepth = 0;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (titleInfoDepth > 0) {
                            titleInfoDepth++;
                        }
                        if (coverpageDepth > 0) {
                            coverpageDepth++;
                        }

                        String localName = reader.getLocalName();
                        if ("title-info".equals(localName)) {
                            titleInfoDepth = 1;
                        } else if (titleInfoDepth > 0 && "coverpage".equals(localName)) {
                            coverpageDepth = 1;
                        } else if (coverpageDepth > 0 && "image".equals(localName) && referencedImageId == null) {
                            String href = getHrefAttribute(reader);
                            if (href != null && href.startsWith("#")) {
                                referencedImageId = href.substring(1);
                            }
                        } else if ("binary".equals(localName) && coverBinaryId == null) {
                            String id = reader.getAttributeValue(null, "id");
                            String contentType = reader.getAttributeValue(null, "content-type");
                            if (StringUtils.containsIgnoreCase(id, "cover")
                                    && StringUtils.startsWithIgnoreCase(contentType, "image/")) {
                                coverBinaryId = id;
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (coverpageDepth > 0) {
                            coverpageDepth--;
                        }
                        if (titleInfoDepth > 0) {
                            titleInfoDepth--;
                        }
                    }
                }
            } finally {
                reader.close();
            }

            String binaryId = StringUtils.defaultIfBlank(coverBinaryId, referencedImageId);
            return StringUtils.isBlank(binaryId) ? null : new CoverCandidate(binaryId);
        }
    }

    private byte[] decodeCoverBinary(File file, String binaryId) throws Exception {
        try (InputStream inputStream = getInputStream(file)) {
            XMLStreamReader reader = createXmlStreamReader(inputStream);
            StringBuilder base64 = null;
            int targetDepth = 0;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (base64 != null) {
                            targetDepth++;
                        } else if ("binary".equals(reader.getLocalName())
                                && binaryId.equals(reader.getAttributeValue(null, "id"))) {
                            base64 = new StringBuilder();
                            targetDepth = 1;
                        }
                    } else if ((event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA
                            || event == XMLStreamConstants.SPACE)
                            && base64 != null) {
                        base64.append(reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT && base64 != null) {
                        if (targetDepth == 1 && "binary".equals(reader.getLocalName())) {
                            return Base64.getMimeDecoder().decode(base64.toString().trim());
                        }
                        targetDepth--;
                    }
                }
            } finally {
                reader.close();
            }
            return null;
        }
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

            XMLStreamReader reader = createXmlStreamReader(inputStream);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT && "description".equals(reader.getLocalName())) {
                        extractDescription(reader, metadataBuilder, authors, categories);
                        break;
                    }
                }
            } finally {
                reader.close();
            }

            metadataBuilder.authors(authors);
            metadataBuilder.categories(categories);
            return metadataBuilder.build();
        } catch (Exception e) {
            log.warn("Failed to extract metadata from FB2: {}", sourceName, e);
            return null;
        }
    }

    private void extractDescription(XMLStreamReader reader, BookMetadata.BookMetadataBuilder builder,
                                    List<String> authors, Set<String> categories) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "title-info" -> extractTitleInfo(reader, builder, authors, categories);
                    case "publish-info" -> extractPublishInfo(reader, builder);
                    case "document-info" -> extractDocumentInfo(reader);
                    default -> {
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
                    case "annotation" -> {
                        String description = readElementText(reader);
                        if (StringUtils.isNotBlank(description)) {
                            builder.description(description);
                        }
                    }
                    case "keywords" -> addKeywords(categories, readElementText(reader));
                    case "date" -> {
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
                    case "lang" -> builder.language(readElementText(reader));
                    case "sequence" -> {
                        extractSequence(reader, builder);
                        skipElement(reader);
                    }
                    default -> {
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "title-info".equals(reader.getLocalName())) {
                return;
            }
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
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "author".equals(reader.getLocalName())) {
                break;
            }
        }

        StringBuilder name = new StringBuilder(64);

        if (StringUtils.isNotBlank(firstName)) {
            name.append(firstName.trim());
        }
        if (StringUtils.isNotBlank(middleName)) {
            if (!name.isEmpty()) name.append(" ");
            name.append(middleName.trim());
        }
        if (StringUtils.isNotBlank(lastName)) {
            if (!name.isEmpty()) name.append(" ");
            name.append(lastName.trim());
        }

        if (name.isEmpty() && StringUtils.isNotBlank(nickname)) {
            name.append(nickname.trim());
        }

        return name.toString();
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
            } catch (NumberFormatException e) {
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
                builder.publishedDate(LocalDate.of(yearValue, 1, 1));
            } catch (NumberFormatException e) {
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
        setXmlProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setXmlProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
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
                return LocalDate.of(year, 1, 1);
            }
        } catch (Exception e) {
            log.debug("Failed to parse date: {}", dateString, e);
        }

        return null;
    }

    private InputStream getInputStream(File file) throws Exception {
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
