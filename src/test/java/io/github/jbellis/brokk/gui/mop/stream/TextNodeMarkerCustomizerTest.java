package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.gui.mop.TestUtil;
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
        System.out.println("Before " + html);
        System.out.println("After " + body.html());

        Document after = Jsoup.parseBodyFragment(body.html());

        // Only the exact whole-word 'test' should be marked
        var marks = after.select("mark");
        assertEquals(1, marks.size(), "Should wrap exactly one occurrence");
        assertEquals("test", marks.first().text(), "Wrapped text should match the search term");
        // Each <mark> must have a numeric data-brokk-id
        marks.forEach(m -> {
            assertTrue(m.hasAttr("data-brokk-id"), "Marker must carry data-brokk-id");
            assertTrue(m.attr("data-brokk-id").matches("\\d+"), "data-brokk-id must be numeric");
        });

        // Ensure other variants remain untouched
        assertTrue(after.text().contains("Testing"), "Partial word 'Testing' must remain");
        assertTrue(after.text().contains("Tester."),  "Partial word 'Tester' must remain");
    }

    @Test
    public void testSample1() {
        String html = "<body>\n" +
                "<p>&lt;p&gt;Make a plan change reprocessForCustomizer such that it can run in compactified content. It may require to do a full re render. an optimization would be to test if a search would hit the plain text and only then re-render&lt;/p&gt;</p>\n" +
                "</body>";
        Document doc = Jsoup.parseBodyFragment(html);
        Element body = doc.body();

        HtmlCustomizer customizer =
                new TextNodeMarkerCustomizer("test", false, true, "<mark>", "</mark>");
        customizer.customize(body);
        System.out.println("Before " + html);
        System.out.println("After " + body.html());
        System.out.println("HTML -----  " + TestUtil.parseHtml(body.html()));

        Document after = Jsoup.parseBodyFragment(body.html());

        // Only the exact whole-word 'test' should be marked
        var marks = after.select("mark");
        assertEquals(1, marks.size(), "Should wrap exactly one occurrence");
        assertEquals("test", marks.first().text(), "Wrapped text should match the search term");
        // Each <mark> must have a numeric data-brokk-id
        marks.forEach(m -> {
            assertTrue(m.hasAttr("data-brokk-id"), "Marker must carry data-brokk-id");
            assertTrue(m.attr("data-brokk-id").matches("\\d+"), "data-brokk-id must be numeric");
        });

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
