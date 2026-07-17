package org.booklore.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import javax.imageio.ImageIO;

/**
 * Turns off {@link ImageIO}'s disk cache, process-wide.
 * <p>
 * {@code ImageIO.setUseCache} defaults to {@code true}, which makes
 * {@code ImageIO.createImageInputStream(InputStream)} back the stream with a
 * {@code FileCacheImageInputStream} - a temp file in {@code java.io.tmpdir}. Every image this
 * application decodes is already a {@code byte[]} in memory, so that temp file buys nothing at all:
 * it is pure IO, and it is IO on the one resource whose exhaustion we are trying to survive. A full
 * disk or an unwritable tmpdir turns a decode into {@code IIOException("Can't create cache file!")},
 * and the callers, reasonably, read a decode failure as a fact about the bytes:
 * {@code FileService.readImage} throws, {@code saveCoverImageFromBytes} reports {@code UNDECODABLE},
 * and the INPX probe writes the book's cover off <em>permanently</em>. A full disk is the canonical
 * transient failure, and it was being recorded as a verdict on the file - because the decode runs
 * before the write, so the {@code WRITE_FAILED} branch built for exactly this case was never
 * reached. {@code CbxMetadataExtractor.canDecode} had the same hole: it catches {@code IOException}
 * and returns false, quietly turning a full disk into "this comic has no readable pages".
 * <p>
 * This is set globally rather than at each decode site on purpose. Nine classes call {@code ImageIO}
 * directly and the setting is JVM-wide static state; a per-site fix is one that the tenth caller
 * silently misses. {@code FileService.readImage} additionally constructs its stream explicitly so it
 * does not depend on this global having been applied, but that only covers the one entry point -
 * this covers the rest, including {@code ImageIO.write} to a {@code ByteArrayOutputStream}, which
 * takes the same cache route on the way out.
 */
@Slf4j
@Configuration
public class ImageIoConfig {

    /**
     * Applied both here and from {@code BookloreApplication.main} before the context starts. Bean
     * initialisation order is not guaranteed, and startup migrations decode images
     * ({@code PopulateCoversAndResizeThumbnailsMigration}), so relying on this bean alone would
     * leave a window in which the setting is not yet in force. It is idempotent.
     */
    @PostConstruct
    public void disableImageIoDiskCache() {
        applyDiskCacheSetting();
    }

    public static void applyDiskCacheSetting() {
        ImageIO.setUseCache(false);
        log.debug("ImageIO disk cache disabled: images are decoded from memory, so a full tmpdir cannot fail a decode");
    }
}
