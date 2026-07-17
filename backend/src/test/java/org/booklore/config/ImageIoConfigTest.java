package org.booklore.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ImageIO.setUseCache} defaults to {@code true}, so decoding from an in-memory {@code byte[]}
 * spools it through a temp file for no benefit whatsoever. When that directory is full or unwritable
 * the decode fails with {@code IIOException("Can't create cache file!")} - and a failed decode is
 * read all the way up the stack as a fact about the <em>bytes</em>: {@code readImage} throws,
 * {@code saveCoverImageFromBytes} says {@code UNDECODABLE}, and the INPX probe records "this book
 * has no usable cover" permanently. A full disk is the canonical transient failure, and wave 4 built
 * {@code SAVE_FAILED} for exactly it - but the decode runs first, so the marker was set before that
 * branch could ever be reached.
 * <p>
 * See {@code FileServiceTest} for that defect driven end to end. This file covers the global, which
 * is what protects the eight other classes that call {@code ImageIO} directly and have no reason to
 * know any of the above.
 */
class ImageIoConfigTest {

    @AfterEach
    void restoreDefault() {
        // JVM-wide static state. Leaving it flipped would silently mask this defect for every other
        // test in the suite - which is a fair description of how it survived in production.
        ImageIO.setUseCache(true);
    }

    @Test
    void disablesTheDiskCacheSoImageIoNeverNeedsATempFile() {
        ImageIO.setUseCache(true);

        new ImageIoConfig().disableImageIoDiskCache();

        assertThat(ImageIO.getUseCache()).isFalse();
    }

    @Test
    void theStaticEntryPointIsIdempotentAndDoesTheSameThing() {
        ImageIO.setUseCache(true);

        ImageIoConfig.applyDiskCacheSetting();
        ImageIoConfig.applyDiskCacheSetting();

        assertThat(ImageIO.getUseCache()).isFalse();
    }
}
