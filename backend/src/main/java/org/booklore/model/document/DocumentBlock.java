package org.booklore.model.document;

/**
 * One paragraph of a Word document, in the single model every consumer reads.
 *
 * @param ordinal      index of the originating paragraph in the <em>source</em> document, not a
 *                     counter over emitted blocks. Skipped content therefore leaves a gap, so
 *                     emitting something later that is dropped today fills that gap instead of
 *                     shifting every ordinal after it.
 * @param headingLevel 1-3 for Word's Heading 1-3 styles, 0 for body text
 * @param text         the paragraph's text, already trimmed and never blank
 */
public record DocumentBlock(int ordinal, int headingLevel, String text) {

    public boolean isHeading() {
        return headingLevel > 0;
    }
}
