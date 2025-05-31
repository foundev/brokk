package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownComponentData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that IncrementalBlockRenderer re-applies a new HtmlCustomizer correctly.
 */
public class IncrementalBlockRendererCustomizerTest {

    @Test
    public void testCustomizerSwitch() {
        String markdown = "alpha beta gamma";

        // Initial customizer highlights 'beta'
        HtmlCustomizer betaHighlighter =
                new TextNodeMarkerCustomizer("beta", true, true, "<mark>", "</mark>");

        // Renderer with the first customizer
        IncrementalBlockRenderer renderer = new IncrementalBlockRenderer(false);
        renderer.setHtmlCustomizer(betaHighlighter);

        // Build initial component data
        String html = renderer.createHtml(markdown);
        List<ComponentData> initial = renderer.buildComponentData(html);

        String initialHtml = extractMarkdownHtml(initial);
        assertTrue(initialHtml.contains("<mark"), "Beta should be highlighted with <mark>");
        assertTrue(initialHtml.contains("beta"),  "Highlight must wrap 'beta'");
        assertFalse(initialHtml.contains("gamma"), "Gamma should not be highlighted yet");

        // Switch to a customizer highlighting 'gamma'
        HtmlCustomizer gammaHighlighter =
                new TextNodeMarkerCustomizer("gamma", true, true, "<mark>", "</mark>");
        renderer.setHtmlCustomizer(gammaHighlighter);

        // Rebuild with the same markdown but new customizer
        List<ComponentData> afterSwitch =
                renderer.buildComponentData(renderer.createHtml(markdown));

        String switchedHtml = extractMarkdownHtml(afterSwitch);
        assertTrue(switchedHtml.contains("<mark"), "Gamma should be highlighted now");
        assertTrue(switchedHtml.contains("gamma"), "Highlight must wrap 'gamma'");
        assertFalse(switchedHtml.contains("beta") && switchedHtml.contains("<mark beta"),
                    "Beta should no longer be highlighted after customizer switch");
    }

    /**
     * Helper to concatenate all HTML snippets from MarkdownComponentData.
     */
    private static String extractMarkdownHtml(List<ComponentData> list) {
        return list.stream()
                   .filter(cd -> cd instanceof MarkdownComponentData)
                   .map(cd -> ((MarkdownComponentData) cd).html())
                   .reduce("", (a, b) -> a + b);
    }
}
