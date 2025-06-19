package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple sanity-checks for {@link SymbolBadgeCustomizer}.
 */
public class SymbolBadgeCustomizerTest {

    private Element apply(String html) {
        Document doc = Jsoup.parse(html);
        Element body  = doc.body();
        new SymbolBadgeCustomizer().customize(body);
        return body;
    }

    @Test
    public void testAnchorWithDataSymbolIdGetsBadge() {
        var body = apply("<p><a href=\"#\" data-symbol-id=\"Foo\">Foo</a></p>");
        assertEquals(1, body.select("a[data-symbol-id] > span.badge-symbol").size(),
                     "Anchor with data-symbol-id should receive a badge");
    }

    @Test
    public void testAnchorWithSymbolTextGetsAttributeAndBadge() {
        var body = apply("<p><a href=\"#\">Foo.Bar</a></p>");
        var anchor = body.selectFirst("a");
        assertNotNull(anchor.attr("data-symbol-id"), "SymbolId attribute should be added");
        assertFalse(anchor.attr("data-symbol-id").isBlank());
        assertEquals(1, anchor.select("span.badge-symbol").size(),
                     "Anchor with symbol text should get a badge");
    }

    @Test
    public void testInlineCodeSymbolGetsBadge() {
        var body = apply("<p>Call <code>Foo.bar()</code> now</p>");
        assertEquals(1, body.select("code + span.badge-symbol").size(),
                     "Inline code symbol should be followed by a badge");
    }
}
