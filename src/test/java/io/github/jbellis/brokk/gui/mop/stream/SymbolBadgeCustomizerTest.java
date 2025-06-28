package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.testutil.MockAnalyzerWrapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple sanity-checks for {@link SymbolBadgeCustomizer}.
 */
public class SymbolBadgeCustomizerTest {

    /**
     * Mock ContextManager that provides a ready analyzer for testing
     */
    public static class MockContextManager implements IContextManager {
        private final MockAnalyzerWrapper analyzerWrapper = new MockAnalyzerWrapper();

        @Override
        public AnalyzerWrapper getAnalyzerWrapper() {
            return analyzerWrapper;
        }
    }

    private Element apply(String html) {
        Document doc = Jsoup.parse(html);
        Element body  = doc.body();
        SymbolBadgeCustomizer.create(new MockContextManager()).customize(body);
        return body;
    }

    @Test
    public void testAnchorWithDataSymbolIdGetsBadge() {
        var body = apply("<p><a href=\"#\" " + BadgeConstants.ATTR_DATA_SYMBOL_ID + "=\"Foo\">Foo</a></p>");
        assertEquals(1, body.select("a[" + BadgeConstants.ATTR_DATA_SYMBOL_ID + "] > span." + BadgeConstants.CLASS_BADGE_SYMBOL + "").size(),
                     "Anchor with data-symbol-id should receive a badge");
    }

    @Test
    public void testAnchorWithSymbolTextGetsAttributeAndBadge() {
        var body = apply("<p><a href=\"#\">Foo.Bar</a></p>");
        var anchor = body.selectFirst("a");
        assertNotNull(anchor.attr(BadgeConstants.ATTR_DATA_SYMBOL_ID), "SymbolId attribute should be added");
        assertFalse(anchor.attr(BadgeConstants.ATTR_DATA_SYMBOL_ID).isBlank());
        assertEquals(1, anchor.select("span." + BadgeConstants.CLASS_BADGE_SYMBOL).size(),
                     "Anchor with symbol text should get a badge");
    }

    @Test
    public void testInlineCodeSymbolGetsBadge() {
        var body = apply("<p>Call <code>Foo.bar()</code> now</p>");
        assertEquals(1, body.select("code + span." + BadgeConstants.CLASS_BADGE_SYMBOL).size(),
                     "Inline code symbol should be followed by a badge");
    }
    
    @Test
    public void testFactoryReturnsDefaultWhenAnalyzerWrapperIsNull() {
        // Create a mock context manager that returns null for analyzer wrapper
        var mockContextManager = new IContextManager() {
            @Override
            public AnalyzerWrapper getAnalyzerWrapper() {
                return null; // Simulate uninitialized state
            }
        };
        
        var customizer = SymbolBadgeCustomizer.create(mockContextManager);
        assertEquals(HtmlCustomizer.DEFAULT, customizer, 
                     "Should return DEFAULT customizer when analyzer wrapper is null");
    }
    
    @Test
    public void testFactoryReturnsDefaultWhenContextManagerIsNull() {
        var customizer = SymbolBadgeCustomizer.create(null);
        assertEquals(HtmlCustomizer.DEFAULT, customizer, 
                     "Should return DEFAULT customizer when context manager is null");
    }
}
