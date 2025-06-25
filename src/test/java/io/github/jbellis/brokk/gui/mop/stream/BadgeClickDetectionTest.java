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
    void testClickableFilenameHasCorrectAttributes() {
        // Test that a clickable filename badge has the correct structure (like original badges)
        // We'll create the expected filename badge structure manually and verify it
        var filenameBadge = new Element("span")
                .addClass("badge")
                .addClass("badge-symbol")
                .addClass("badge-file")
                .addClass("clickable-badge")
                .text("README.md")
                .attr("title", "file:README.md:id:1")
                .attr("style", "cursor: pointer; text-decoration: underline; color: #5a7fb8; font-size: 0.9em; margin-left: 2px;");
        
        // Verify the badge has all the expected attributes for click detection
        assertTrue(filenameBadge.hasClass("badge-file"), "Badge should have badge-file class");
        assertTrue(filenameBadge.hasClass("clickable-badge"), "Badge should have clickable-badge class");
        assertEquals("README.md", filenameBadge.text());
        assertTrue(filenameBadge.attr("title").startsWith("file:"), "Title should start with 'file:'");
        assertTrue(filenameBadge.attr("title").contains("README.md"), "Title should contain filename");
        assertTrue(filenameBadge.attr("style").contains("underline"), "Style should include underline");
        assertTrue(filenameBadge.attr("style").contains("pointer"), "Style should include pointer cursor");
        assertTrue(filenameBadge.attr("style").matches(".*color:\\s*#[0-9a-fA-F]{6}.*"), "Style should include a hex color");
        
        System.out.println("Expected clickable filename badge HTML: " + filenameBadge.outerHtml());
        
        // This matches the structure created by createClickableFilenameBadge in SymbolBadgeCustomizer
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
    
    @Test
    void testFilenameBecomesClickable() {
        // Test that a filename in code gets made clickable by the customizer
        var mockContextManager = new SymbolBadgeCustomizerTest.MockContextManager();
        var customizer = SymbolBadgeCustomizer.create(mockContextManager);
        
        // Create HTML with a filename reference
        var html = "<p>Check the <code>README.md</code> file</p>";
        var doc = Jsoup.parse(html);
        var body = doc.body();
        
        customizer.customize(body);
        
        // The code element should now be transformed into a clickable filename badge
        var codeElements = body.select("code");
        assertEquals(1, codeElements.size(), "Should have one code element");
        
        var codeElement = codeElements.first();
        
        // The code element should now have badge classes and be clickable
        assertTrue(codeElement.hasClass("badge-file"), "Code element should have badge-file class");
        assertTrue(codeElement.hasClass("clickable-badge"), "Code element should have clickable-badge class");
        assertTrue(codeElement.attr("title").startsWith("file:"), "Title should start with 'file:'");
        assertTrue(codeElement.attr("title").contains("README.md"), "Title should contain filename");
        assertTrue(codeElement.attr("style").contains("underline"), "Style should include underline");
        assertTrue(codeElement.attr("style").matches(".*color:\\s*#[0-9a-fA-F]{6}.*"), "Style should include a hex color");
        assertEquals("README.md", codeElement.text(), "Element should show the filename");
        
        System.out.println("Processed clickable filename: " + body.html());
    }
}