package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HtmlCustomizer that wraps every appearance of a given term occurring in normal
 * text nodes with configurable start/end wrappers.  Works at the DOM level,
 * leaving existing markup untouched.
 */
public final class TextNodeMarkerCustomizer implements HtmlCustomizer {

    private final Pattern pattern;
    private final String wrapperStart;
    private final String wrapperEnd;

    /**
     * Tags inside which we deliberately skip highlighting.
     */
    private static final Set<String> SKIP_TAGS =
            Set.of("code", "pre", "a", "script", "style", "img");

    /**
     * Attribute used to mark wrapper elements created by this customizer.
     * Any text inside an element bearing this attribute will be ignored on
     * subsequent traversals, preventing repeated wrapping.
     */
    private static final String BROKK_MARKER_ATTR = "data-brokk-marker";

    /**
     * @param term          the term to highlight (must not be empty)
     * @param caseSensitive true if the match should be case-sensitive
     * @param wholeWord     true to require word boundaries around the term
     * @param wrapperStart  snippet inserted before the match (may contain HTML)
     * @param wrapperEnd    snippet inserted after  the match (may contain HTML)
     */
    public TextNodeMarkerCustomizer(String term,
                                    boolean caseSensitive,
                                    boolean wholeWord,
                                    String wrapperStart,
                                    String wrapperEnd) {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(wrapperStart, "wrapperStart");
        Objects.requireNonNull(wrapperEnd, "wrapperEnd");
        if (term.isEmpty()) {
            throw new IllegalArgumentException("term must not be empty");
        }
        this.wrapperStart = wrapperStart;
        this.wrapperEnd = wrapperEnd;

        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        var regex = Pattern.quote(term);
        if (wholeWord) {
            regex = "\\b" + regex + "\\b";
        }
        this.pattern = Pattern.compile(regex, flags);
    }

    @Override
    public void customize(Element root) {
        if (root == null) {
            return;
        }
        NodeTraversor.traverse(new Visitor(), root);
    }

    private class Visitor implements NodeVisitor {
        @Override
        public void head(Node node, int depth) {
            if (node instanceof TextNode textNode) {
                process(textNode);
            }
        }
        @Override public void tail(Node node, int depth) { /* no-op */ }

        private void process(TextNode tn) {
            if (tn.isBlank()) return;
            if (hasAncestorMarker(tn)) return;
            if (tn.parent() instanceof Element el &&
                    SKIP_TAGS.contains(el.tagName().toLowerCase())) {
                return; // skip inside forbidden tags
            }

            String text = tn.getWholeText();
            System.out.println("TO CUSTMIZE " + text);
            Matcher m = pattern.matcher(text);
            if (!m.find()) return; // nothing to highlight

            List<Node> pieces = new ArrayList<>();
            int last = 0;
            do {
                int start = m.start(), end = m.end();
                if (start > last) {
                    pieces.add(new TextNode(text.substring(last, start)));
                }
                String match = text.substring(start, end);
                String snippetHtml = wrapperStart + match + wrapperEnd;
                var fragment = Jsoup.parseBodyFragment(snippetHtml).body().childNodes();
                for (Node fragNode : fragment) {
                    if (fragNode instanceof Element fragEl) {
                        fragEl.attr(BROKK_MARKER_ATTR, "1");
                    }
                }
                pieces.addAll(fragment);
                last = end;
            } while (m.find());

            if (last < text.length()) {
                pieces.add(new TextNode(text.substring(last)));
            }

            Node ref = tn;
            // insert each generated fragment AFTER the current node to avoid
            // disturbing NodeTraversor's iteration order
            for (Node n : pieces) {
                ref.after(n);
                ref = n;
            }
            // finally remove the original text node
            tn.remove();
        }

        /**
         * Returns true if the node has an ancestor element that already
         * carries the custom wrapper attribute, meaning it has been processed.
         */
        private boolean hasAncestorMarker(Node node) {
            for (Node p = node.parent(); p != null; p = p.parent()) {
                if (p instanceof Element e && e.hasAttr(BROKK_MARKER_ATTR)) {
                    return true;
                }
            }
            return false;
        }
    }
}
