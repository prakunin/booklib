package org.booklore.service.enrichment.queue;

/**
 * How the queue orders work. Fixed in code rather than configurable: these are relative urgencies,
 * not preferences, and the only thing a user could achieve by changing them is making the button
 * they just pressed feel broken.
 */
public final class EnrichmentPriority {

    /** The user pressed a button on one book and is watching. */
    public static final int SINGLE_BOOK = 100;

    /** The user selected books and asked for them explicitly. */
    public static final int SELECTION = 80;

    /** The user just opened this book, so it is about to be looked at. */
    public static final int READER_OPEN = 50;

    /** Filling gaps left by an import nobody is waiting on. */
    public static final int IMPORT_TOP_UP = 20;

    /** A whole-library sweep, which by definition has time. */
    public static final int LIBRARY_SWEEP = 10;

    private EnrichmentPriority() {
    }
}
