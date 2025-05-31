package io.github.jbellis.brokk.gui.mop.stream;

/**
 * Functional interface allowing callers to tweak the raw HTML generated from
 * Markdown before Swing components are built.
 */
@FunctionalInterface
public interface HtmlCustomizer {
    /**
     * @param html raw HTML generated from Markdown
     * @return customized HTML (may be the same instance)
     */
    String customize(String html);
}
