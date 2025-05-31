package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TextNodeMarkerCustomizer}.
 */
public class TextNodeMarkerCustomizerTest {

    /**
     * Verify that whole-word, case-insensitive matches are wrapped with the
     * requested <mark>…</mark> snippet and that partial words are ignored.
     */
    @Test
    public void testWholeWordMatch() {
        String html = "<p>This is a test. Testing highlight. Tester.</p>";
        Document doc = Jsoup.parseBodyFragment(html);
        Element body = doc.body();

        HtmlCustomizer customizer =
                new TextNodeMarkerCustomizer("test", false, true, "<mark>", "</mark>");
        customizer.customize(body);

        Document after = Jsoup.parseBodyFragment(body.html());

        // Only the exact whole-word 'test' should be marked
        var marks = after.select("mark");
        assertEquals(1, marks.size(), "Should wrap exactly one occurrence");
        assertEquals("test", marks.first().text(), "Wrapped text should match the search term");

        // Ensure other variants remain untouched
        assertTrue(after.text().contains("Testing"), "Partial word 'Testing' must remain");
        assertTrue(after.text().contains("Tester."),  "Partial word 'Tester' must remain");
    }

    /**
     * Verify that matches inside <code> tags are NOT highlighted.
     */
    @Test
    public void testSkipInsideCodeTag() {
        String html = "<p>test</p><code>test</code>";
        Document doc = Jsoup.parseBodyFragment(html);
        Element body = doc.body();

        HtmlCustomizer customizer =
                new TextNodeMarkerCustomizer("test", false, true, "<mark>", "</mark>");
        customizer.customize(body);

        Document after = Jsoup.parseBodyFragment(body.html());

        // One match outside <code>, none inside
        assertEquals(1, after.select("mark").size(), "Only one mark outside <code> expected");
        assertEquals(0, after.select("code mark").size(), "No marks allowed inside <code>");
    }
}
