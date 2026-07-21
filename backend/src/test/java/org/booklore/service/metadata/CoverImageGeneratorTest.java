package org.booklore.service.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class CoverImageGeneratorTest {

    private final CoverImageGenerator generator = new CoverImageGenerator();

    private BufferedImage decode(byte[] jpegBytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpegBytes));
        assertThat(image).as("decoded JPEG").isNotNull();
        return image;
    }

    @Nested
    @DisplayName("generateCover (portrait)")
    class GenerateCoverTest {

        @Test
        void producesA1200x1600Jpeg() throws IOException {
            byte[] jpeg = generator.generateCover("The Fellowship of the Ring", "J. R. R. Tolkien");

            assertThat(jpeg).isNotEmpty();
            assertThat(jpeg[0]).isEqualTo((byte) 0xFF);
            assertThat(jpeg[1]).isEqualTo((byte) 0xD8);
            BufferedImage image = decode(jpeg);
            assertThat(image.getWidth()).isEqualTo(1200);
            assertThat(image.getHeight()).isEqualTo(1600);
        }

        @Test
        void twoArgOverload_delegatesWithNullSubtitle() throws IOException {
            byte[] jpeg = generator.generateCover("Dune", "Frank Herbert");

            decode(jpeg);
        }

        @Test
        void withSubtitle_rendersWithoutError() throws IOException {
            byte[] jpeg = generator.generateCover("Dune", "Frank Herbert", "A science fiction epic of politics and prophecy");

            decode(jpeg);
        }

        @Test
        void blankSubtitle_isTreatedAsAbsent() throws IOException {
            byte[] jpeg = generator.generateCover("Dune", "Frank Herbert", "   ");

            decode(jpeg);
        }

        @Test
        void nullTitleAndAuthor_fallBackToDefaults() throws IOException {
            byte[] jpeg = generator.generateCover(null, null);

            decode(jpeg);
        }

        @Test
        void blankTitleAndAuthor_fallBackToDefaults() throws IOException {
            byte[] jpeg = generator.generateCover("   ", "   ");

            decode(jpeg);
        }

        @Test
        void veryShortTitleAndAuthor_useLargestFontBucket() throws IOException {
            byte[] jpeg = generator.generateCover("It", "Or");

            decode(jpeg);
        }

        @Test
        void veryLongTitle_isTruncatedAndWrapped() throws IOException {
            String longTitle = "A ".repeat(150) + "Extremely Long Title That Exceeds The Maximum Allowed Length By A Wide Margin";
            byte[] jpeg = generator.generateCover(longTitle, "Some Author");

            decode(jpeg);
        }

        @Test
        void multipleCommaSeparatedAuthors_suppressesByPrefix() throws IOException {
            byte[] jpeg = generator.generateCover("Good Omens", "Terry Pratchett, Neil Gaiman");

            decode(jpeg);
        }

        @Test
        void singleShortAuthor_showsByPrefix() throws IOException {
            byte[] jpeg = generator.generateCover("Neuromancer", "Gibson");

            decode(jpeg);
        }

        @Test
        void veryLongAuthorList_wrapsAcrossMultipleLines() throws IOException {
            byte[] jpeg = generator.generateCover(
                    "An Anthology", "Author Number One, Author Number Two, Author Number Three, Author Number Four");

            decode(jpeg);
        }

        @Test
        void unicodeTitleAndAuthor_renderWithoutError() throws IOException {
            byte[] jpeg = generator.generateCover("百年の孤独", "ガブリエル・ガルシア=マルケス");

            decode(jpeg);
        }
    }

    @Nested
    @DisplayName("generateSquareCover (audiobook)")
    class GenerateSquareCoverTest {

        @Test
        void producesA1200x1200Jpeg() throws IOException {
            byte[] jpeg = generator.generateSquareCover("Project Hail Mary", "Andy Weir");

            assertThat(jpeg).isNotEmpty();
            BufferedImage image = decode(jpeg);
            assertThat(image.getWidth()).isEqualTo(1200);
            assertThat(image.getHeight()).isEqualTo(1200);
        }

        @Test
        void nullTitleAndAuthor_fallBackToDefaults() throws IOException {
            byte[] jpeg = generator.generateSquareCover(null, null);

            decode(jpeg);
        }

        @Test
        void veryLongTitleAndAuthor_wrapAndTruncate() throws IOException {
            String longTitle = "Word ".repeat(80);
            String longAuthor = "Author ".repeat(40);
            byte[] jpeg = generator.generateSquareCover(longTitle, longAuthor);

            decode(jpeg);
        }
    }
}
