package io.github.jbellis.brokk.gui.mop.stream;

/**
 * Functional interface allowing callers to tweak the raw HTML generated from
 * Markdown before Swing components are built.
 */
@FunctionalInterface
public interface HtmlCustomizer {

    /**
     * Identity customizer that returns the HTML unchanged.
     */
    HtmlCustomizer DEFAULT = html -> html;

    /**
     * Convenience accessor for the {@link #DEFAULT} instance.
     *
     * @return an identity HtmlCustomizer
     */
    static HtmlCustomizer noOp() {
        return DEFAULT;
    }
    /**
     * @param html raw HTML generated from Markdown
     * @return customized HTML (may be the same instance)
     */
    String customize(String html);
}
