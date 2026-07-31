package org.booklore.service.djvu;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
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

    /** Share of a page's pixels that must already be pure paper or pure ink for it to count as text. */
    private static final double EXTREME_PIXEL_RATIO = 0.95;
    private static final int LIGHT_CHANNEL_MINIMUM = 240;
    private static final int DARK_CHANNEL_MAXIMUM = 32;
    private static final int MID_GREY = 128;
    /** Longest edge a photographic page is stored at. Well above what any reader viewport shows. */
    private static final int MAX_PHOTOGRAPHIC_EDGE_PIXELS = 2000;

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
        BufferedImage image = toolRunner.renderPage(source, pageNumber, 0);

        // The page is sized in points to match the rendered image, so a word's DjVu coordinates -
        // which are in the document's own page space - only have to be scaled by the same factor the
        // render used, and never translated or flipped.
        PDRectangle box = new PDRectangle(image.getWidth(), image.getHeight());
        PDPage page = new PDPage(box);
        document.addPage(page);

        PDImageXObject xObject = encodePage(document, image);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(xObject, 0, 0, box.getWidth(), box.getHeight());
            if (font != null) {
                drawHiddenText(content, font, source, info, pageNumber, box);
            }
        }
    }

    /**
     * Chooses how the page goes into the PDF, which is the single biggest thing about the file the
     * user ends up with.
     * <p>
     * DjVu exists for scanned text, and scanned text is bitonal: whole books are two colours. JPEG
     * is the wrong codec for that twice over - it is built for photographs, so it rings around
     * letter edges, and it cannot exploit the redundancy that makes such a page compress at all. A
     * 181-page scan that is 3.5 MB as DjVu came out at 211 MB as JPEG-in-PDF, which is not a file
     * anyone wants to stream into a reader. CCITT Group 4 is the encoding built for exactly this
     * and brings it back to the same order of magnitude as the source.
     * <p>
     * Anything that is not bitonal - a page with photographs, a colour plate - keeps JPEG, where it
     * is the right choice and G4 would not be usable at all. Those pages are also the only ones
     * scaled down: a bitonal page costs almost nothing at full resolution and is sharpest there,
     * while a photographic one at 8.4 megapixels costs about a megabyte and shows no more than the
     * capped version does in any reader.
     */
    private PDImageXObject encodePage(PDDocument document, BufferedImage image) throws IOException {
        BufferedImage bitonal = asBitonal(image);
        if (bitonal != null) {
            return CCITTFactory.createFromImage(document, bitonal);
        }
        // 0.8 rather than the 0.75 default: these are scans, and the artefacts a lower quality
        // leaves around text are exactly what makes a scan hard to read.
        return JPEGFactory.createFromImage(document, capped(image), 0.8f);
    }

    /**
     * Scales a page down so its longest edge is at most {@value #MAX_PHOTOGRAPHIC_EDGE_PIXELS}.
     * <p>
     * Only the stored pixels shrink. The PDF page keeps the box it was given - the page's natural
     * size - and the smaller image is drawn to fill it, so pages stay a consistent size through the
     * document and the hidden text, which is placed against that box, needs no adjustment.
     */
    private BufferedImage capped(BufferedImage image) {
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest <= MAX_PHOTOGRAPHIC_EDGE_PIXELS) {
            return image;
        }
        double scale = (double) MAX_PHOTOGRAPHIC_EDGE_PIXELS / longest;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));

        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /**
     * @return the page as a 1-bit image when it is a scan of text, or {@code null} when it is not
     * and belongs in a photographic codec instead.
     * <p>
     * "A scan of text" is decided by where the pixels sit rather than by how many colours there are.
     * A page of black type on white paper renders with a tail of greys along every glyph edge - one
     * such page here has 731 distinct colours - so counting colours calls it a photograph. What it
     * actually is shows in the distribution: {@value #EXTREME_PIXEL_RATIO} of its pixels are already
     * pure paper or pure ink, and the rest is the anti-aliasing between them. Thresholding that back
     * to two levels is what a bitonal scanner would have produced to begin with.
     * <p>
     * The tuning was measured against a real 181-page scan: its text pages sit at 96-100% extreme
     * pixels, its illustrated pages at 74-88%, and its colour cover at 43%. The cut is deliberately
     * below the text pages and well above the illustrations, so a page with a picture on it keeps
     * its greys.
     */
    private BufferedImage asBitonal(BufferedImage image) {
        if (image.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            return null;
        }
        // Straight over the raster rather than through getRGB: a page is eight megapixels and
        // getRGB goes through the colour model on every one of them, which turns a scan of the
        // bytes into minutes per book.
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        if (pixels.length < 3) {
            return null;
        }

        long extreme = 0;
        long total = pixels.length / 3;
        for (int offset = 0; offset < pixels.length; offset += 3) {
            if (isExtreme(pixels, offset)) {
                extreme++;
            }
        }
        if (extreme < total * EXTREME_PIXEL_RATIO) {
            return null;
        }

        return threshold(image, pixels);
    }

    private boolean isExtreme(byte[] pixels, int offset) {
        int blue = pixels[offset] & 0xFF;
        int green = pixels[offset + 1] & 0xFF;
        int red = pixels[offset + 2] & 0xFF;
        return (Math.min(blue, Math.min(green, red)) >= LIGHT_CHANNEL_MINIMUM)
                || (Math.max(blue, Math.max(green, red)) <= DARK_CHANNEL_MAXIMUM);
    }

    /**
     * Thresholds straight into the 1-bit raster rather than drawing the image into it: drawing
     * dithers, which scatters the greys along glyph edges into speckle and makes the page both
     * uglier and larger once G4 has to encode the noise.
     */
    private BufferedImage threshold(BufferedImage image, byte[] pixels) {
        BufferedImage binary = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        WritableRaster raster = binary.getRaster();
        int width = image.getWidth();

        for (int y = 0; y < image.getHeight(); y++) {
            int row = y * width * 3;
            for (int x = 0; x < width; x++) {
                int offset = row + x * 3;
                int luminance = ((pixels[offset] & 0xFF) + (pixels[offset + 1] & 0xFF) + (pixels[offset + 2] & 0xFF)) / 3;
                raster.setSample(x, y, 0, luminance < MID_GREY ? 0 : 1);
            }
        }
        return binary;
    }

    private int pixel(byte[] pixels, int offset) {
        return (pixels[offset] & 0xFF) << 16 | (pixels[offset + 1] & 0xFF) << 8 | (pixels[offset + 2] & 0xFF);
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
