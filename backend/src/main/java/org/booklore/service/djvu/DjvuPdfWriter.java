package org.booklore.service.djvu;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Builds the PDF rendition of a DjVu document: each page as an image, with the document's own
 * hidden text laid invisibly on top so the result is searchable and selectable.
 * <p>
 * This is assembled here rather than handed to djvulibre because djvulibre cannot do it. Its only
 * route to PDF is {@code djvups} into ghostscript, and {@code djvups} has no option to carry the
 * text layer across - the result is pictures in a PDF wrapper, which is exactly what the page reader
 * already shows. Writing the file ourselves is what makes the rendition worth having, and it also
 * means the page images are the JPEGs we already rendered, so the file's size is ours to control.
 * <p>
 * The text is drawn in render mode 3 - invisible - at the coordinates DjVu records for each word.
 * Both formats put the origin at the bottom left, so the boxes carry over untouched.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DjvuPdfWriter {

    /**
     * Text is invisible, so the font is never seen; it is needed only so the glyphs can be encoded
     * and read back out. It must still cover the scripts these documents are written in, which is
     * why a Unicode font is looked up rather than one of PDF's built-in WinAnsi fonts - those cannot
     * encode Cyrillic at all, and Cyrillic scans are exactly what DjVu is full of.
     */
    private static final List<String> FONT_CANDIDATES = List.of(
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "/usr/share/fonts/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf");

    private final DjvuToolRunner toolRunner;

    /**
     * Writes the rendition.
     *
     * @param pageProgress called with each page number as it is finished, for a caller that wants
     *                     to report progress. May be {@code null}.
     * @throws DjvuToolException if a page cannot be rendered or the file cannot be written
     */
    public void write(Path source, DjvuDocumentInfo info, Path target, IntConsumer pageProgress) {
        try (PDDocument document = new PDDocument()) {
            PDType0Font font = loadFont(document, source);

            for (int page = 1; page <= info.pageCount(); page++) {
                addPage(document, source, info, font, page);
                if (pageProgress != null) {
                    pageProgress.accept(page);
                }
            }

            Files.createDirectories(target.getParent());
            document.save(target.toFile());
        } catch (IOException e) {
            throw new DjvuToolException("Failed to write the PDF rendition of " + source.getFileName(), e);
        }
    }

    private void addPage(PDDocument document, Path source, DjvuDocumentInfo info, PDType0Font font, int pageNumber)
            throws IOException {
        byte[] jpeg = renderPage(source, pageNumber);
        BufferedImage image = decode(jpeg, source, pageNumber);

        // The page is sized in points to match the rendered image, so a word's DjVu coordinates -
        // which are in the document's own page space - only have to be scaled by the same factor the
        // render used, and never translated or flipped.
        PDRectangle box = new PDRectangle(image.getWidth(), image.getHeight());
        PDPage page = new PDPage(box);
        document.addPage(page);

        PDImageXObject xObject = JPEGFactory.createFromStream(document, new ByteArrayInputStream(jpeg));
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(xObject, 0, 0, box.getWidth(), box.getHeight());
            if (font != null) {
                drawHiddenText(content, font, source, info, pageNumber, box);
            }
        }
    }

    private byte[] renderPage(Path source, int pageNumber) {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        toolRunner.renderPageAsJpeg(source, pageNumber, 0, jpeg);
        return jpeg.toByteArray();
    }

    private BufferedImage decode(byte[] jpeg, Path source, int pageNumber) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (image == null) {
            throw new DjvuToolException("Page " + pageNumber + " of " + source.getFileName()
                    + " rendered to bytes that are not an image");
        }
        return image;
    }

    /**
     * Lays the page's words invisibly over the image. A word whose glyphs the font cannot encode is
     * skipped rather than failing the document: losing one word's searchability is a far smaller
     * loss than losing the whole rendition, and a scan in a script the bundled font does not cover
     * still deserves its pages.
     */
    private void drawHiddenText(PDPageContentStream content, PDType0Font font, Path source,
                                DjvuDocumentInfo info, int pageNumber, PDRectangle box) throws IOException {
        List<DjvuTextWord> words = readText(source, pageNumber);
        if (words.isEmpty()) {
            return;
        }

        double scale = pageScale(info, pageNumber, box);
        content.beginText();
        content.setRenderingMode(RenderingMode.NEITHER);
        for (DjvuTextWord word : words) {
            float height = (float) (word.height() * scale);
            float width = (float) (word.width() * scale);
            if (height <= 0 || width <= 0) {
                continue;
            }
            try {
                float natural = font.getStringWidth(word.text()) / 1000f * height;
                content.setFont(font, height);
                // Stretch the invisible string across the box the word actually occupies, so a
                // selection follows the ink rather than drifting away from it across a line.
                content.setHorizontalScaling(natural > 0 ? 100f * width / natural : 100f);
                content.setTextMatrix(Matrix.getTranslateInstance(
                        (float) (word.left() * scale), (float) (word.bottom() * scale)));
                content.showText(word.text());
            } catch (IllegalArgumentException e) {
                log.debug("Skipping a word the rendition font cannot encode: {}", e.getMessage());
            }
        }
        content.endText();
    }

    private List<DjvuTextWord> readText(Path source, int pageNumber) {
        try {
            return toolRunner.pageText(source, pageNumber);
        } catch (DjvuToolException e) {
            log.debug("No hidden text read for page {} of {}: {}", pageNumber, source.getFileName(), e.getMessage());
            return List.of();
        }
    }

    /**
     * How much the render enlarged or shrank the page. Text coordinates are in the document's own
     * page space, the image may not be, and getting this wrong is what puts the text layer a
     * plausible-looking few percent away from the ink.
     */
    private double pageScale(DjvuDocumentInfo info, int pageNumber, PDRectangle box) {
        List<DjvuDocumentInfo.PageSize> sizes = info.pageSizes();
        if (pageNumber > sizes.size()) {
            return 1d;
        }
        int naturalWidth = sizes.get(pageNumber - 1).width();
        return naturalWidth > 0 ? box.getWidth() / (double) naturalWidth : 1d;
    }

    /**
     * @return the font to write hidden text with, or {@code null} when the image carries none - in
     * which case the rendition is still built, just without a text layer.
     */
    private PDType0Font loadFont(PDDocument document, Path source) {
        for (String candidate : FONT_CANDIDATES) {
            Path path = Path.of(candidate);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try (InputStream font = Files.newInputStream(path)) {
                return PDType0Font.load(document, font, true);
            } catch (IOException e) {
                log.warn("Could not load {} for the DjVu text layer: {}", candidate, e.getMessage());
            }
        }
        log.warn("No Unicode font found, so the PDF rendition of {} will have no searchable text. "
                + "The image is expected to provide one of {}", source.getFileName(), FONT_CANDIDATES);
        return null;
    }
}
