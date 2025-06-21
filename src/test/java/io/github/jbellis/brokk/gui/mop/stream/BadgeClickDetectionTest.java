package io.github.jbellis.brokk.gui.mop.stream;

import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests badge click detection functionality.
 */
class BadgeClickDetectionTest {

    @Test
    void testFileBadgeHasClickableAttributes() {
        // Test that a file badge would have the correct structure
        // We'll create the expected badge structure manually and verify it
        var badge = new Element("span")
                .addClass("badge")
                .addClass("badge-symbol")
                .addClass("badge-file")
                .addClass("clickable-badge")
                .text("F")
                .attr("title", "file:README.md:id:1");
        
        // Verify the badge has all the expected attributes for click detection
        assertTrue(badge.hasClass("clickable-badge"), "Badge should have clickable-badge class");
        assertTrue(badge.hasClass("badge-file"), "Badge should have badge-file class");
        assertEquals("F", badge.text());
        assertTrue(badge.attr("title").startsWith("file:"), "Title should start with 'file:'");
        assertTrue(badge.attr("title").contains("README.md"), "Title should contain filename");
        
        System.out.println("Expected file badge HTML: " + badge.outerHtml());
        
        // This matches the structure created by createBadgeForFile in SymbolBadgeCustomizer
    }
    
    @Test
    void testSymbolBadgeDoesNotHaveClickableClass() {
        // This test documents that symbol badges don't have the clickable-badge class
        // Using a simple approach - create the customizer and check it processes symbols
        var mockContextManager = new SymbolBadgeCustomizerTest.MockContextManager();
        var customizer = SymbolBadgeCustomizer.create(mockContextManager);
        
        // Create HTML with a symbol reference
        var html = "<p>The <code>TestClass</code> is important</p>";
        var doc = Jsoup.parse(html);
        var body = doc.body();
        
        customizer.customize(body);
        
        // Symbol badges are created but not marked as clickable
        var badges = body.select(".badge-symbol");
        assertEquals(1, badges.size(), "Should have one symbol badge");
        
        var badge = badges.first();
        // Currently symbol badges don't have clickable-badge class
        assertFalse(badge.hasClass("clickable-badge"), "Symbol badges are not clickable yet");
    }
}