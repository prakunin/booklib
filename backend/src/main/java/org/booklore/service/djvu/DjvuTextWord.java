package org.booklore.service.djvu;

/**
 * One word of a DjVu document's hidden text layer, with the box it occupies on the page.
 * <p>
 * Coordinates are in the page's own image space with the origin at the bottom left - the same
 * convention PDF uses, which is why the rendition can place these words without flipping anything.
 */
public record DjvuTextWord(String text, int left, int bottom, int right, int top) {

    public int width() {
        return right - left;
    }

    public int height() {
        return top - bottom;
    }
}
