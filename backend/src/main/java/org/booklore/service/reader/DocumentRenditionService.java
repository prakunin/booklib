package org.booklore.service.reader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.document.DocumentBlock;
import org.booklore.model.document.DocumentContent;
import org.booklore.model.dto.response.EpubBookInfo;
import org.booklore.model.dto.response.EpubManifestItem;
import org.booklore.model.dto.response.EpubSpineItem;
import org.booklore.model.dto.response.EpubTocItem;
import org.booklore.service.document.DocumentContentExtractor;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Serves a Word document to the EPUB reader as a rendition that is never assembled: no archive is
 * built and nothing is written to disk. The document is parsed once into {@link DocumentContent},
 * split into spine chunks, and each chunk is rendered to XHTML on request.
 * <p>
 * Chunking is a pure function of the parsed blocks, which are themselves a pure function of the
 * source bytes. That is what keeps a saved reading position valid: a cache miss re-derives a
 * byte-identical rendition, so invalidation is a freshness mechanism and never moves a reader.
 * Changing {@link #MAX_BLOCKS_PER_CHUNK} or the heading level that opens a chunk is therefore a
 * breaking change for every position already stored, not a tuning knob.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentRenditionService {

    private static final int MAX_CACHE_ENTRIES = 50;

    /** Caps chunk size for documents that carry no headings at all, so the reader never gets one huge page. */
    private static final int MAX_BLOCKS_PER_CHUNK = 150;

    /** Headings at or above this level open a new chunk; deeper ones only appear in the table of contents. */
    private static final int CHUNK_BREAK_HEADING_LEVEL = 2;

    private static final String XHTML_MEDIA_TYPE = "application/xhtml+xml";
    private static final String STYLESHEET_HREF = "styles/document.css";
    private static final String CONTAINER_HREF = "META-INF/container.xml";
    private static final String OPF_HREF = "content.opf";
    private static final String NAV_HREF = "nav.xhtml";

    /**
     * Fixed on purpose. The reader builds the book by reading {@code META-INF/container.xml} and the
     * OPF it points at, exactly as it would for a real EPUB, so the package must carry a modified
     * timestamp - and a real one would make two copies of the same document differ byte for byte,
     * breaking the determinism reading positions depend on.
     */
    private static final String FIXED_MODIFIED = "1970-01-01T00:00:00Z";
    private static final String STYLESHEET = """
            body { margin: 0 auto; padding: 0 1em; }
            h1, h2, h3 { margin: 1.4em 0 0.6em; line-height: 1.25; }
            p { margin: 0 0 0.9em; text-align: justify; }
            """;

    private final DocumentContentExtractor extractor;

    private final Cache<String, CachedRendition> renditionCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    /**
     * @param renderedSize byte length of this chunk's XHTML. The reader derives its progress
     *                     percentage from the manifest sizes, so leaving them at zero makes the
     *                     total zero and the percentage NaN.
     */
    private record Chunk(String href, String id, List<DocumentBlock> blocks, long renderedSize) {
    }

    private record CachedRendition(long lastModified, List<Chunk> chunks, Map<String, Chunk> byHref) {
    }

    public boolean supports(Path path) {
        return extractor.supports(path.getFileName().toString());
    }

    public EpubBookInfo buildBookInfo(Path path) throws IOException {
        List<Chunk> chunks = rendition(path).chunks();

        List<EpubManifestItem> manifest = new ArrayList<>(chunks.size() + 1);
        List<EpubSpineItem> spine = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            manifest.add(EpubManifestItem.builder()
                    .id(chunk.id())
                    .href(chunk.href())
                    .mediaType(XHTML_MEDIA_TYPE)
                    .properties(List.of())
                    .size(chunk.renderedSize())
                    .build());
            spine.add(EpubSpineItem.builder()
                    .idref(chunk.id())
                    .href(chunk.href())
                    .mediaType(XHTML_MEDIA_TYPE)
                    .linear(true)
                    .build());
        }
        manifest.add(EpubManifestItem.builder()
                .id("document-css")
                .href(STYLESHEET_HREF)
                .mediaType("text/css")
                .properties(List.of())
                .size(STYLESHEET.getBytes(StandardCharsets.UTF_8).length)
                .build());
        manifest.add(EpubManifestItem.builder()
                .id("nav")
                .href(NAV_HREF)
                .mediaType(XHTML_MEDIA_TYPE)
                .properties(List.of("nav"))
                .size(navXhtml(path).getBytes(StandardCharsets.UTF_8).length)
                .build());

        return EpubBookInfo.builder()
                .containerPath(OPF_HREF)
                .rootPath("")
                .spine(spine)
                .manifest(manifest)
                .toc(buildToc(chunks))
                .metadata(Map.of())
                .coverPath(null)
                .build();
    }

    public void streamResource(Path path, String href, OutputStream outputStream) throws IOException {
        String clean = href.startsWith("/") ? href.substring(1) : href;
        String rendered = switch (clean) {
            case CONTAINER_HREF -> containerXml();
            case OPF_HREF -> opf(path);
            case NAV_HREF -> navXhtml(path);
            case STYLESHEET_HREF -> STYLESHEET;
            default -> {
                Chunk chunk = rendition(path).byHref().get(clean);
                if (chunk == null) {
                    throw new FileNotFoundException("Not part of the document rendition: " + href);
                }
                yield renderChunk(chunk);
            }
        };
        outputStream.write(rendered.getBytes(StandardCharsets.UTF_8));
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

    private String opf(Path path) throws IOException {
        List<Chunk> chunks = rendition(path).chunks();
        String title = escape(titleOf(path));

        StringBuilder manifest = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        manifest.append("    <item id=\"nav\" href=\"%s\" media-type=\"%s\" properties=\"nav\"/>"
                .formatted(NAV_HREF, XHTML_MEDIA_TYPE)).append('\n');
        manifest.append("    <item id=\"document-css\" href=\"%s\" media-type=\"text/css\"/>"
                .formatted(STYLESHEET_HREF)).append('\n');
        for (Chunk chunk : chunks) {
            manifest.append("    <item id=\"%s\" href=\"%s\" media-type=\"%s\"/>"
                    .formatted(chunk.id(), chunk.href(), XHTML_MEDIA_TYPE)).append('\n');
            spine.append("    <itemref idref=\"%s\"/>".formatted(chunk.id())).append('\n');
        }

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="bookid">urn:booklib:document:%s</dc:identifier>
                    <dc:title>%s</dc:title>
                    <dc:language>und</dc:language>
                    <meta property="dcterms:modified">%s</meta>
                  </metadata>
                  <manifest>
                %s  </manifest>
                  <spine>
                %s  </spine>
                </package>
                """.formatted(title, title, FIXED_MODIFIED, manifest, spine);
    }

    private String navXhtml(Path path) throws IOException {
        EpubTocItem toc = buildToc(rendition(path).chunks());
        StringBuilder items = new StringBuilder();
        appendNavItems(toc.getChildren(), items);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><meta charset="utf-8"/><title>Contents</title></head>
                <body>
                <nav epub:type="toc" id="toc">
                <ol>
                %s</ol>
                </nav>
                </body>
                </html>
                """.formatted(items);
    }

    private void appendNavItems(List<EpubTocItem> items, StringBuilder out) {
        for (EpubTocItem item : items) {
            out.append("<li><a href=\"").append(item.getHref()).append("\">")
                    .append(escape(item.getLabel())).append("</a>");
            if (item.getChildren() != null && !item.getChildren().isEmpty()) {
                out.append("\n<ol>\n");
                appendNavItems(item.getChildren(), out);
                out.append("</ol>\n");
            }
            out.append("</li>\n");
        }
    }

    private String titleOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private CachedRendition rendition(Path path) throws IOException {
        String key = path.toString();
        long lastModified = Files.getLastModifiedTime(path).toMillis();
        CachedRendition cached = renditionCache.getIfPresent(key);
        if (cached != null && cached.lastModified() == lastModified) {
            return cached;
        }

        DocumentContent content = extractor.extract(path.toFile());
        List<Chunk> chunks = chunk(content);
        Map<String, Chunk> byHref = chunks.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Chunk::href, c -> c));
        CachedRendition fresh = new CachedRendition(lastModified, chunks, byHref);
        renditionCache.put(key, fresh);
        return fresh;
    }

    /**
     * Splits blocks into spine chunks. Deterministic by construction: it depends only on the block
     * list, never on time, locale, or iteration order of an unordered collection.
     */
    private List<Chunk> chunk(DocumentContent content) {
        List<Chunk> chunks = new ArrayList<>();
        List<DocumentBlock> current = new ArrayList<>();
        for (DocumentBlock block : content.blocks()) {
            boolean opensChunk = block.isHeading() && block.headingLevel() <= CHUNK_BREAK_HEADING_LEVEL;
            if (!current.isEmpty() && (opensChunk || current.size() >= MAX_BLOCKS_PER_CHUNK)) {
                chunks.add(newChunk(chunks.size(), current));
                current = new ArrayList<>();
            }
            current.add(block);
        }
        if (!current.isEmpty()) {
            chunks.add(newChunk(chunks.size(), current));
        }
        if (chunks.isEmpty()) {
            chunks.add(newChunk(0, List.of()));
        }
        return List.copyOf(chunks);
    }

    private Chunk newChunk(int index, List<DocumentBlock> blocks) {
        String id = "chunk-%04d".formatted(index + 1);
        Chunk unsized = new Chunk("text/" + id + ".xhtml", id, List.copyOf(blocks), 0L);
        long size = renderChunk(unsized).getBytes(StandardCharsets.UTF_8).length;
        return new Chunk(unsized.href(), unsized.id(), unsized.blocks(), size);
    }

    /**
     * Nests the headings by level. A document with no heading styles yields a single entry, which is
     * still a structurally valid table of contents rather than an empty one.
     */
    private EpubTocItem buildToc(List<Chunk> chunks) {
        List<EpubTocItem> roots = new ArrayList<>();
        Deque<LevelledItem> open = new ArrayDeque<>();

        for (Chunk chunk : chunks) {
            for (DocumentBlock block : chunk.blocks()) {
                if (!block.isHeading()) {
                    continue;
                }
                EpubTocItem item = EpubTocItem.builder()
                        .label(block.text())
                        .href(chunk.href() + "#b" + block.ordinal())
                        .children(new ArrayList<>())
                        .build();
                while (!open.isEmpty() && open.peek().level() >= block.headingLevel()) {
                    open.pop();
                }
                if (open.isEmpty()) {
                    roots.add(item);
                } else {
                    open.peek().item().getChildren().add(item);
                }
                open.push(new LevelledItem(block.headingLevel(), item));
            }
        }

        if (roots.isEmpty()) {
            roots.add(EpubTocItem.builder()
                    .label("Document")
                    .href(chunks.getFirst().href())
                    .children(List.of())
                    .build());
        }
        return EpubTocItem.builder().label("").href("").children(roots).build();
    }

    private record LevelledItem(int level, EpubTocItem item) {
    }

    private String renderChunk(Chunk chunk) {
        StringBuilder body = new StringBuilder(chunk.blocks().size() * 64);
        for (DocumentBlock block : chunk.blocks()) {
            String tag = block.isHeading() ? "h" + block.headingLevel() : "p";
            body.append('<').append(tag).append(" id=\"b").append(block.ordinal()).append("\">")
                    .append(escape(block.text()))
                    .append("</").append(tag).append(">\n");
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><meta charset="utf-8"/><title>%s</title>\
                <link rel="stylesheet" type="text/css" href="../%s"/></head>
                <body>
                %s</body>
                </html>
                """.formatted(chunk.id(), STYLESHEET_HREF, body);
    }

    private String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
