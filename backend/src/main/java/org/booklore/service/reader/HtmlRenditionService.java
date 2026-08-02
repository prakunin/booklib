package org.booklore.service.reader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.response.EpubBookInfo;
import org.booklore.model.dto.response.EpubManifestItem;
import org.booklore.model.dto.response.EpubSpineItem;
import org.booklore.model.dto.response.EpubTocItem;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class HtmlRenditionService {

    private static final String CONTAINER_HREF = "META-INF/container.xml";
    private static final String OPF_HREF = "content.opf";
    private static final String CONTENT_HREF = "content.xhtml";
    private static final String FIXED_MODIFIED = "1970-01-01T00:00:00Z";
    private static final long MAX_HTML_BYTES = 16L * 1024 * 1024;
    private static final long MAX_CACHE_WEIGHT = 64L * 1024 * 1024;
    private static final int RESOURCE_WEIGHT_BYTES = 4 * 1024;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("gif", "jpg", "jpeg", "png", "webp");
    private static final Set<String> UNSAFE_ELEMENTS = Set.of(
            "script", "style", "link", "base", "iframe", "frame", "frameset", "object", "embed",
            "form", "input", "button", "textarea", "select", "option", "audio", "video", "source", "svg");

    private final ArchivedBookContentService archivedBookContentService;
    private final Cache<Long, CachedRendition> cache = Caffeine.<Long, CachedRendition>newBuilder()
            .maximumWeight(MAX_CACHE_WEIGHT)
            .weigher((Long bookId, CachedRendition rendition) -> Math.toIntExact(Math.min(
                    Integer.MAX_VALUE,
                    (long) rendition.xhtml().length + (long) rendition.resources().size() * RESOURCE_WEIGHT_BYTES)))
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    private record Resource(String archiveEntry, String mediaType, long size) {
    }

    private record CachedRendition(long lastModified, byte[] xhtml, Map<String, Resource> resources,
                                   EpubBookInfo bookInfo, String title) {
    }

    public boolean supports(BookFileEntity bookFile) {
        return bookFile != null && bookFile.getBookType() == BookFileType.HTML && bookFile.isArchivedSource();
    }

    public EpubBookInfo buildBookInfo(BookFileEntity bookFile) throws IOException {
        return rendition(bookFile).bookInfo();
    }

    public void streamResource(BookFileEntity bookFile, String href, OutputStream output) throws IOException {
        String clean = cleanHref(href);
        CachedRendition rendition = rendition(bookFile);
        switch (clean) {
            case CONTAINER_HREF -> output.write(containerXml().getBytes(StandardCharsets.UTF_8));
            case OPF_HREF -> output.write(opf(rendition).getBytes(StandardCharsets.UTF_8));
            case CONTENT_HREF -> output.write(rendition.xhtml());
            default -> {
                Resource resource = rendition.resources().get(clean);
                if (resource == null) {
                    throw new FileNotFoundException("Not part of the HTML rendition: " + href);
                }
                archivedBookContentService.streamPublicationEntry(bookFile, resource.archiveEntry(), output);
            }
        }
    }

    private CachedRendition rendition(BookFileEntity bookFile) throws IOException {
        Path htmlPath = archivedBookContentService.resolve(bookFile);
        long htmlSize = Files.size(htmlPath);
        if (htmlSize < 0 || htmlSize > MAX_HTML_BYTES) {
            throw new IOException("HTML publication exceeds the rendition size limit");
        }
        long lastModified = Files.getLastModifiedTime(htmlPath).toMillis();
        CachedRendition cached = cache.getIfPresent(bookFile.getId());
        if (cached != null && cached.lastModified() == lastModified) {
            return cached;
        }

        List<ArchivedBookContentService.ArchivedEntry> entries =
                archivedBookContentService.listPublicationEntries(bookFile);
        Map<String, ArchivedBookContentService.ArchivedEntry> available = new LinkedHashMap<>();
        entries.forEach(entry -> available.put(entry.name(), entry));
        String htmlEntry = archivedBookContentService.publicationEntryName(bookFile);
        String baseDirectory = directoryOf(htmlEntry);

        Document document;
        try (InputStream input = Files.newInputStream(htmlPath)) {
            document = Jsoup.parse(input, null, "");
        }
        sanitizeElements(document);

        Map<String, Resource> resources = new LinkedHashMap<>();
        int index = 0;
        for (Element image : document.select("img[src]")) {
            String resolved = resolveResource(baseDirectory, image.attr("src"));
            ArchivedBookContentService.ArchivedEntry entry = resolved == null ? null : available.get(resolved);
            String extension = resolved == null ? "" : extension(resolved);
            if (entry == null || !IMAGE_EXTENSIONS.contains(extension)) {
                image.removeAttr("src");
                continue;
            }
            String synthetic = "resources/%04d.%s".formatted(++index, extension);
            image.attr("src", synthetic);
            resources.put(synthetic, new Resource(resolved, mediaType(extension), entry.size()));
        }

        document.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .charset(StandardCharsets.UTF_8)
                .prettyPrint(false);
        document.select("meta[charset]").remove();
        document.head().prependElement("meta").attr("charset", "utf-8");
        if (document.documentType() != null) {
            document.documentType().remove();
        }
        document.selectFirst("html").attr("xmlns", "http://www.w3.org/1999/xhtml");
        byte[] xhtml = document.outerHtml().getBytes(StandardCharsets.UTF_8);
        if (xhtml.length > MAX_HTML_BYTES) {
            throw new IOException("Sanitized HTML publication exceeds the rendition size limit");
        }
        String title = document.title().isBlank() ? titleOf(bookFile.getFileName()) : document.title();
        EpubBookInfo info = bookInfo(title, xhtml.length, resources);
        CachedRendition fresh = new CachedRendition(lastModified, xhtml, Map.copyOf(resources), info, title);
        cache.put(bookFile.getId(), fresh);
        return fresh;
    }

    private void sanitizeElements(Document document) {
        document.select("meta[http-equiv], meta[http-equiv=refresh]").remove();
        document.getAllElements().stream()
                .filter(element -> UNSAFE_ELEMENTS.contains(element.normalName()))
                .toList()
                .forEach(Element::remove);
        for (Element element : document.getAllElements()) {
            List<String> unsafeAttributes = new ArrayList<>();
            for (Attribute attribute : element.attributes()) {
                String key = attribute.getKey().toLowerCase(Locale.ROOT);
                if (key.startsWith("on") || key.equals("style") || key.equals("srcset")
                        || key.equals("background") || key.equals("action") || key.equals("formaction")) {
                    unsafeAttributes.add(attribute.getKey());
                }
            }
            unsafeAttributes.forEach(element::removeAttr);
            if (element.hasAttr("href") && !element.attr("href").startsWith("#")) {
                element.removeAttr("href");
            }
            if (!element.normalName().equals("img")) {
                element.removeAttr("src");
            }
        }
    }

    private String resolveResource(String baseDirectory, String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawPath() == null
                    || uri.getRawPath().startsWith("/") || uri.getRawPath().isBlank()) {
                return null;
            }
            String rawPath = uri.getRawPath();
            String lowerRawPath = rawPath.toLowerCase(Locale.ROOT);
            if (lowerRawPath.contains("%2f") || lowerRawPath.contains("%5c") || lowerRawPath.contains("%00")) {
                return null;
            }
            String decodedPath = uri.getPath();
            if (decodedPath == null || decodedPath.indexOf('\\') >= 0 || decodedPath.indexOf('\0') >= 0) {
                return null;
            }
            for (String segment : decodedPath.split("/")) {
                if (segment.equals("..")) {
                    return null;
                }
            }
            String combined = baseDirectory + decodedPath;
            URI normalized = new URI(null, null, "/" + combined, null).normalize();
            String path = normalized.getPath().substring(1);
            return path.startsWith("../") || path.isBlank() ? null : path;
        } catch (URISyntaxException | IllegalArgumentException _) {
            return null;
        }
    }

    private EpubBookInfo bookInfo(String title, long xhtmlSize, Map<String, Resource> resources) {
        List<EpubManifestItem> manifest = new ArrayList<>(resources.size() + 1);
        manifest.add(EpubManifestItem.builder()
                .id("html-content")
                .href(CONTENT_HREF)
                .mediaType("application/xhtml+xml")
                .properties(List.of())
                .size(xhtmlSize)
                .build());
        int index = 0;
        for (Map.Entry<String, Resource> entry : resources.entrySet()) {
            manifest.add(EpubManifestItem.builder()
                    .id("resource-" + ++index)
                    .href(entry.getKey())
                    .mediaType(entry.getValue().mediaType())
                    .properties(List.of())
                    .size(Math.max(0, entry.getValue().size()))
                    .build());
        }
        return EpubBookInfo.builder()
                .containerPath(OPF_HREF)
                .rootPath("")
                .spine(List.of(EpubSpineItem.builder()
                        .idref("html-content")
                        .href(CONTENT_HREF)
                        .mediaType("application/xhtml+xml")
                        .linear(true)
                        .build()))
                .manifest(manifest)
                .toc(EpubTocItem.builder().label(title).href(CONTENT_HREF).build())
                .metadata(Map.of("title", title))
                .coverPath(null)
                .build();
    }

    private String containerXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="%s" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.formatted(OPF_HREF);
    }

    private String opf(CachedRendition rendition) {
        StringBuilder manifest = new StringBuilder();
        manifest.append("    <item id=\"html-content\" href=\"").append(CONTENT_HREF)
                .append("\" media-type=\"application/xhtml+xml\"/>\n");
        int index = 0;
        for (Map.Entry<String, Resource> entry : rendition.resources().entrySet()) {
            manifest.append("    <item id=\"resource-").append(++index).append("\" href=\"")
                    .append(entry.getKey()).append("\" media-type=\"")
                    .append(entry.getValue().mediaType()).append("\"/>\n");
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="bookid">urn:booklib:html:%s</dc:identifier>
                    <dc:title>%s</dc:title>
                    <dc:language>und</dc:language>
                    <meta property="dcterms:modified">%s</meta>
                  </metadata>
                  <manifest>
                %s  </manifest>
                  <spine><itemref idref="html-content"/></spine>
                </package>
                """.formatted(escape(rendition.title()), escape(rendition.title()), FIXED_MODIFIED, manifest);
    }

    private String directoryOf(String entryName) {
        int slash = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
        return slash < 0 ? "" : entryName.substring(0, slash + 1);
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String mediaType(String extension) {
        return switch (extension) {
            case "gif" -> "image/gif";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    private String titleOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String cleanHref(String href) {
        return href != null && href.startsWith("/") ? href.substring(1) : href;
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
