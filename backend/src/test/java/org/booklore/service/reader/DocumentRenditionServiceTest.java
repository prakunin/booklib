package org.booklore.service.reader;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.booklore.model.document.DocumentBlock;
import org.booklore.model.document.DocumentContent;
import org.booklore.model.dto.response.EpubBookInfo;
import org.booklore.model.dto.response.EpubTocItem;
import org.booklore.service.document.DocumentContentExtractor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRenditionServiceTest {

    private final DocumentRenditionService service = new DocumentRenditionService(new DocumentContentExtractor());

    @TempDir
    Path tempDir;

    @FunctionalInterface
    private interface DocxBuilder {
        void build(XWPFDocument doc);
    }

    private Path writeDocx(String name, DocxBuilder builder) throws Exception {
        Path file = tempDir.resolve(name);
        try (XWPFDocument doc = new XWPFDocument()) {
            builder.build(doc);
            try (OutputStream out = Files.newOutputStream(file)) {
                doc.write(out);
            }
        }
        return file;
    }

    private void paragraph(XWPFDocument doc, String style, String text) {
        XWPFParagraph p = doc.createParagraph();
        if (style != null) {
            p.setStyle(style);
        }
        p.createRun().setText(text);
    }

    private String render(Path file, String href) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamResource(file, href, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Nested
    class Determinism {

        @Test
        void twoIdenticalDocumentsProduceAByteIdenticalRendition() throws Exception {
            DocxBuilder body = doc -> {
                paragraph(doc, "Heading1", "One");
                paragraph(doc, null, "Alpha");
                paragraph(doc, "Heading1", "Two");
                paragraph(doc, null, "Beta");
            };
            Path first = writeDocx("first.docx", body);
            Path second = writeDocx("second.docx", body);

            EpubBookInfo firstInfo = service.buildBookInfo(first);
            EpubBookInfo secondInfo = service.buildBookInfo(second);

            assertThat(firstInfo.getSpine()).extracting("href")
                    .isEqualTo(secondInfo.getSpine().stream().map(s -> s.getHref()).toList());
            // Chunk boundaries and rendered markup are a pure function of the source, which is what
            // keeps a stored reading position valid across a cache miss.
            assertThat(render(first, firstInfo.getSpine().getFirst().getHref()))
                    .isEqualTo(render(second, secondInfo.getSpine().getFirst().getHref()));
        }
    }

    @Nested
    class Chunking {

        @Test
        void opensANewChunkAtEachTopLevelHeading() throws Exception {
            Path file = writeDocx("chunks.docx", doc -> {
                paragraph(doc, "Heading1", "One");
                paragraph(doc, null, "Alpha");
                paragraph(doc, "Heading1", "Two");
                paragraph(doc, null, "Beta");
                paragraph(doc, "Heading1", "Three");
            });

            EpubBookInfo info = service.buildBookInfo(file);

            assertThat(info.getSpine()).hasSize(3);
            assertThat(info.getSpine().getFirst().getHref()).isEqualTo("text/chunk-0001.xhtml");
        }

        @Test
        void aDocumentWithNoHeadingsStillYieldsOneReadableChunk() throws Exception {
            Path file = writeDocx("flat.docx", doc -> paragraph(doc, null, "Just prose"));

            EpubBookInfo info = service.buildBookInfo(file);

            assertThat(info.getSpine()).hasSize(1);
            assertThat(render(file, info.getSpine().getFirst().getHref())).contains("Just prose");
        }
    }

    @Nested
    class ManifestSizes {

        @Test
        void reportsRealByteSizesSoTheReaderCanComputeProgress() throws Exception {
            Path file = writeDocx("sizes.docx", doc -> {
                paragraph(doc, "Heading1", "One");
                paragraph(doc, null, "Alpha");
                paragraph(doc, "Heading1", "Two");
                paragraph(doc, null, "Beta");
            });

            EpubBookInfo info = service.buildBookInfo(file);

            // The reader derives its percentage from these; all-zero sizes make the total zero and
            // the displayed progress NaN.
            assertThat(info.getManifest()).isNotEmpty()
                    .allSatisfy(item -> assertThat(item.getSize()).isPositive());
        }

        @Test
        void aChunkDeclaresExactlyAsManyBytesAsItStreams() throws Exception {
            Path file = writeDocx("exact.docx", doc -> paragraph(doc, null, "Some prose"));

            EpubBookInfo info = service.buildBookInfo(file);
            String href = info.getSpine().getFirst().getHref();
            long declared = info.getManifest().stream()
                    .filter(i -> href.equals(i.getHref()))
                    .findFirst().orElseThrow().getSize();

            assertThat(render(file, href).getBytes(StandardCharsets.UTF_8)).hasSize(Math.toIntExact(declared));
        }
    }

    @Nested
    class TableOfContents {

        @Test
        void nestsByHeadingLevel() throws Exception {
            Path file = writeDocx("toc.docx", doc -> {
                paragraph(doc, "Heading1", "Part One");
                paragraph(doc, "Heading2", "Section A");
                paragraph(doc, "Heading1", "Part Two");
            });

            EpubTocItem toc = service.buildBookInfo(file).getToc();

            assertThat(toc.getChildren()).extracting(EpubTocItem::getLabel)
                    .containsExactly("Part One", "Part Two");
            assertThat(toc.getChildren().getFirst().getChildren()).extracting(EpubTocItem::getLabel)
                    .containsExactly("Section A");
        }

        @Test
        void aDocumentWithNoHeadingsGetsASingleEntryRatherThanAnEmptyContents() throws Exception {
            Path file = writeDocx("noheadings.docx", doc -> paragraph(doc, null, "Prose only"));

            EpubTocItem toc = service.buildBookInfo(file).getToc();

            assertThat(toc.getChildren()).hasSize(1);
        }
    }

    @Nested
    class Rendering {

        @Test
        void escapesMarkupSoDocumentTextCannotBreakOutOfTheXhtml() throws Exception {
            Path file = writeDocx("escape.docx", doc -> paragraph(doc, null, "a < b & c > d"));

            EpubBookInfo info = service.buildBookInfo(file);

            assertThat(render(file, info.getSpine().getFirst().getHref()))
                    .contains("a &lt; b &amp; c &gt; d");
        }

        @Test
        void removesXmlControlCharactersWithoutDroppingValidWhitespace() throws Exception {
            Path file = tempDir.resolve("controls.doc");
            Files.write(file, new byte[]{0});
            DocumentContentExtractor controlCharacterExtractor = new DocumentContentExtractor() {
                @Override
                public DocumentContent extract(File ignored) throws IOException {
                    return new DocumentContent(
                            List.of(new DocumentBlock(0, 0, "before\u000Eafter\tline\nnext")),
                            null,
                            null,
                            null);
                }
            };
            DocumentRenditionService controlCharacterService =
                    new DocumentRenditionService(controlCharacterExtractor);

            EpubBookInfo info = controlCharacterService.buildBookInfo(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            controlCharacterService.streamResource(file, info.getSpine().getFirst().getHref(), output);

            assertThat(output.toString(StandardCharsets.UTF_8))
                    .contains("before after\tline\nnext")
                    .doesNotContain("\u000E");
        }

        @Test
        void anchorsEachBlockOnItsSourceOrdinal() throws Exception {
            Path file = writeDocx("anchors.docx", doc -> {
                paragraph(doc, "Heading1", "Title");
                paragraph(doc, null, "Body");
            });

            EpubBookInfo info = service.buildBookInfo(file);

            assertThat(render(file, info.getSpine().getFirst().getHref()))
                    .contains("<h1 id=\"b0\">Title</h1>")
                    .contains("<p id=\"b1\">Body</p>");
        }
    }
}
