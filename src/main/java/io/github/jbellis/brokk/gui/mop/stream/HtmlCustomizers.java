package io.github.jbellis.brokk.gui.mop.stream;

/**
 * Convenience helpers for frequently-used {@link HtmlCustomizer} instances.
 */
public final class HtmlCustomizers {
    private HtmlCustomizers() {}

    /**
     * Wraps whole-word, case-insensitive matches of {@code term} in asterisks.
     */
    public static HtmlCustomizer wrapWithAsterisks(String term) {
        return new TextNodeMarkerCustomizer(term, false, true, "*", "*");
    }

    /**
     * Wraps matches in a &lt;mark&gt; tag (useful for high-visibility highlight).
     */
    public static HtmlCustomizer highlightWithMarkTag(String term) {
        return new TextNodeMarkerCustomizer(term, false, true, "<mark>", "</mark>");
    }

    /**
     * Generic factory for customised wrappers.
     */
    public static HtmlCustomizer wrapWithStrings(String term,
                                                 boolean caseSensitive,
                                                 boolean wholeWord,
                                                 String wrapperStart,
                                                 String wrapperEnd) {
        return new TextNodeMarkerCustomizer(term, caseSensitive, wholeWord,
                                            wrapperStart, wrapperEnd);
    }
}
