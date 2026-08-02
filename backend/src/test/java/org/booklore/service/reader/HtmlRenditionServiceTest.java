package org.booklore.service.reader;

import org.booklore.model.dto.response.EpubBookInfo;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HtmlRenditionServiceTest {

    @Mock
    private ArchivedBookContentService archivedBookContentService;

    @Test
    void decodesSanitizesAndAllowListsLocalImages(@TempDir Path root) throws Exception {
        BookFileEntity bookFile = BookFileEntity.builder()
                .id(42L)
                .fileName("letter.html")
                .bookType(BookFileType.HTML)
                .sourceArchive("outer.zip")
                .sourceArchiveEntry("nested locator")
                .build();
        String html = """
                <html><head><meta charset="windows-1251"><title>Письмо</title></head>
                <body onload="alert(1)"><script>alert(1)</script>
                <img src="img/00.gif" onerror="alert(2)"><img src="../secret.jpg">
                <img src="img/My%20Cover.jpg">
                <img src="https://example.test/tracker.gif"><a href="https://example.test">remote</a>
                <svg><image xlink:href="https://example.test/tracker.gif"/></svg>
                <iframe src="img/00.gif"></iframe></body></html>
                """;
        Path materialized = root.resolve("letter.html");
        Files.write(materialized, html.getBytes(Charset.forName("windows-1251")));
        when(archivedBookContentService.resolve(bookFile)).thenReturn(materialized);
        when(archivedBookContentService.publicationEntryName(bookFile)).thenReturn("letter.html");
        when(archivedBookContentService.listPublicationEntries(bookFile)).thenReturn(List.of(
                new ArchivedBookContentService.ArchivedEntry("letter.html", Files.size(materialized)),
                new ArchivedBookContentService.ArchivedEntry("img/00.gif", 3),
                new ArchivedBookContentService.ArchivedEntry("img/My Cover.jpg", 5),
                new ArchivedBookContentService.ArchivedEntry("secret.jpg", 6)));
        doAnswer(invocation -> {
            ((java.io.OutputStream) invocation.getArgument(2)).write("gif".getBytes());
            return null;
        }).when(archivedBookContentService).streamPublicationEntry(eq(bookFile), eq("img/00.gif"), any());
        HtmlRenditionService service = new HtmlRenditionService(archivedBookContentService);

        EpubBookInfo info = service.buildBookInfo(bookFile);
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        service.streamResource(bookFile, "content.xhtml", content);
        String xhtml = content.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(info.getSpine()).singleElement().extracting(item -> item.getHref()).isEqualTo("content.xhtml");
        assertThat(info.getManifest()).extracting(item -> item.getHref())
                .containsExactly("content.xhtml", "resources/0001.gif", "resources/0002.jpg");
        assertThat(xhtml).contains("Письмо", "src=\"resources/0001.gif\"", "src=\"resources/0002.jpg\"")
                .doesNotContain("script", "onload", "onerror", "iframe", "svg", "xlink", "https://", "../secret.jpg");

        ByteArrayOutputStream image = new ByteArrayOutputStream();
        service.streamResource(bookFile, "resources/0001.gif", image);
        assertThat(image.toString()).isEqualTo("gif");
        assertThatThrownBy(() -> service.streamResource(bookFile, "resources/../../secret.jpg",
                new ByteArrayOutputStream())).isInstanceOf(java.io.FileNotFoundException.class);
    }
}
