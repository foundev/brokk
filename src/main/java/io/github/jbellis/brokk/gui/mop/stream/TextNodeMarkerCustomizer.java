package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * HtmlCustomizer that wraps every appearance of a given term occurring in normal
 * text nodes with configurable start/end wrappers.  Works at the DOM level,
 * leaving existing markup untouched.
 */
public final class TextNodeMarkerCustomizer implements HtmlCustomizer {
    private static final Logger logger = LogManager.getLogger(TextNodeMarkerCustomizer.class);

    private final Pattern pattern;
    private final String wrapperStart;
    private final String wrapperEnd;
    private final String searchTerm;

    /**
     * Tags inside which we deliberately skip highlighting.
     */
    private static final Set<String> SKIP_TAGS =
            Set.of("script", "style", "img");

    /**
     * Attribute used to mark wrapper elements created by this customizer.
     * Any text inside an element bearing this attribute will be ignored on
     * subsequent traversals, preventing repeated wrapping.
     */
    private static final String BROKK_MARKER_ATTR = "data-brokk-marker";

    /**
     * CSS class specifically for search highlight markers created by this customizer.
     * This allows us to clean up only search highlights without affecting other markers.
     */
    private static final String SEARCH_MARKER_CLASS = "brokk-search-marker";

    /**
     * Attribute that carries a stable numeric id for later component lookup.
     */
    private static final String BROKK_ID_ATTR = "data-brokk-id";

    /**
     * Feature flag to control HTML file generation for debugging.
     * Set to true to enable writing search.html files after each search operation.
     * Disabled by default for production use.
     */
    private static final boolean ENABLE_HTML_DEBUG_OUTPUT = true;

    /**
     * Global id generator for {@link #BROKK_ID_ATTR}. Thread-safe, simple monotonic counter.
     * We deliberately do not reset between customizer instances because we only
     * need *uniqueness* inside a single JVM session.
     */
    private static final AtomicInteger ID_GEN =
            new AtomicInteger(1);

    // Unique ID for this customizer type
    private static final int CUSTOMIZER_ID = 1002;

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
        this.searchTerm = term;
        this.wrapperStart = wrapperStart;
        this.wrapperEnd = wrapperEnd;

        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        var regex = Pattern.quote(term);
        if (wholeWord) {
            regex = "\\b" + regex + "\\b";
        }
        this.pattern = Pattern.compile(regex, flags);

        logger.debug("Created TextNodeMarkerCustomizer for term='{}', caseSensitive={}, wholeWord={}",
                    term, caseSensitive, wholeWord);
    }

    /**
     * Fast check used by renderers to see if this customizer could possibly
     * influence the supplied text.  Returns {@code true} if the term occurs
     * at least once according to the current configuration.
     *
     * @param text input to test (may be {@code null})
     */
    public boolean mightMatch(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return pattern.matcher(text).find();
    }

    @Override
    public void customize(Element root) {
        logger.debug("Starting search customization for term '{}' on DOM: {}",
                    searchTerm, root.html().length() > 200 ? root.html().substring(0, 200) + "..." : root.html());

        // ------------------------------------------------------------------
        // 1.  Remove any highlight wrappers left from a previous search.
        //     We unwrap ONLY elements that are search markers created by this customizer.
        //     This preserves other markers like file badges which use different classes.
        //     After this step the DOM contains *no* search markup but preserves other content.
        // ------------------------------------------------------------------
        var existingMarkers = root.select("." + SEARCH_MARKER_CLASS);
        if (!existingMarkers.isEmpty()) {
            logger.warn("REMOVING {} search markers", existingMarkers.size());
            for (var el : existingMarkers) {
                logger.warn("Removing search marker: {}", el.outerHtml());
            }
        }
        for (var el : existingMarkers) {
            // Copy to avoid ConcurrentModificationException while rewriting
            var children = new ArrayList<Node>(el.childNodes());
            for (Node child : children) {
                el.before(child);
            }
            el.remove();
        }

        // ------------------------------------------------------------------
        // 2.  Add fresh highlights by collecting text nodes first to avoid concurrent modification
        // ------------------------------------------------------------------
        var visitor = new Visitor();

        // First pass: collect all text nodes to avoid concurrent modification during traversal
        var allTextNodes = new ArrayList<TextNode>();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode textNode && !textNode.isBlank()) {
                    allTextNodes.add(textNode);
                }
            }
            @Override public void tail(Node node, int depth) { /* no-op */ }
        }, root);

        // Second pass: process collected text nodes
        for (TextNode textNode : allTextNodes) {
            // Check if the text node is still in the document (not removed by previous processing)
            if (textNode.parent() != null) {
                visitor.process(textNode);
            }
        }

        logger.debug("Search customization complete. Found {} matches for term '{}'",
                    visitor.matchCount, searchTerm);
        
        // Print complete HTML structure with embedded CSS at the end of search (if debug flag is enabled)
        if (ENABLE_HTML_DEBUG_OUTPUT && visitor.matchCount > 0) {
            writeCompleteHtmlWithCss(root);
        }
    }


    private class Visitor {
        int matchCount = 0;

        public void process(TextNode tn) {
            if (tn.isBlank()) return;
            if (hasAncestorMarker(tn)) {
                logger.trace("Skipping text node with ancestor marker: '{}'", tn.text());
                return;
            }
            if (tn.parent() instanceof Element el &&
                    SKIP_TAGS.contains(el.tagName().toLowerCase(Locale.ROOT))) {
                logger.trace("Skipping text node in forbidden tag '{}': '{}'", el.tagName(), tn.text());
                return; // skip inside forbidden tags
            }

            String text = tn.getWholeText();
            // Quick check if there's anything to highlight
            if (!pattern.matcher(text).find()) {
                logger.trace("No matches found in text: '{}'", text);
                return; // nothing to highlight
            }

            logger.debug("Found matches in text node: '{}'", text);

            // Find all matches in this text node
            Matcher m = pattern.matcher(text);
            List<MatchRange> ranges = new ArrayList<>();
            while (m.find()) {
                String matchedText = text.substring(m.start(), m.end());
                logger.debug("Found match: '{}' at position {}-{}", matchedText, m.start(), m.end());
                ranges.add(new MatchRange(m.start(), m.end()));
                matchCount++;
            }

            // Apply all highlights to this text node at once
            applyHighlightsToTextNode(tn, text, ranges);
            logger.debug("Applied {} highlights to text node", ranges.size());
        }

        /**
         * Returns true if the node has an ancestor element that is a search marker,
         * meaning it has already been processed by this customizer.
         */
        private boolean hasAncestorMarker(Node node) {
            for (Node p = node.parent(); p != null; p = p.parent()) {
                if (p instanceof Element e && e.hasClass(SEARCH_MARKER_CLASS)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class MatchRange {
        final int start;
        final int end;

        MatchRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Apply multiple highlights to a single text node.
     * Ranges are already sorted by start position from the matcher.
     * Special handling for file badges to preserve clickability across the entire badge.
     */
    private void applyHighlightsToTextNode(TextNode textNode, String text, List<MatchRange> ranges) {
        // Check if this text node is inside a file badge
        boolean isInFileBadge = isInsideFileBadge(textNode);

        if (isInFileBadge) {
            // Special handling for file badges - wrap the entire content to ensure full clickability
            applyHighlightsToFileBadgeText(textNode, text, ranges);
        } else {
            // Normal handling for non-file-badge text
            applyHighlightsToNormalText(textNode, text, ranges);
        }
    }

    /**
     * Apply highlights to text inside file badges, preserving the parent file badge as the clickable element.
     * The key insight: The parent clickable-file-badge should handle mouse events, not individual child elements.
     * We only add styling spans for highlighting without making them separately clickable.
     */
    private void applyHighlightsToFileBadgeText(TextNode textNode, String text, List<MatchRange> ranges) {
        List<Node> pieces = new ArrayList<>();
        int lastEnd = 0;

        for (MatchRange range : ranges) {
            // Add text before this match as plain text (parent will handle clicks)
            if (range.start > lastEnd) {
                String beforeText = text.substring(lastEnd, range.start);
                pieces.add(new TextNode(beforeText));
            }

            // Add the highlighted match with search styling only
            String matchText = text.substring(range.start, range.end);
            int markerId = ID_GEN.getAndIncrement();

            var snippetHtml = wrapperStart + matchText + wrapperEnd;
            var fragment = Jsoup.parseBodyFragment(snippetHtml).body().childNodes();
            for (Node fragNode : fragment) {
                if (fragNode instanceof Element fragEl) {
                    fragEl.attr(BROKK_MARKER_ATTR, "1");
                    fragEl.attr(BROKK_ID_ATTR, Integer.toString(markerId));
                    fragEl.addClass(SEARCH_MARKER_CLASS); // Add search marker class for cleanup

                    // Do NOT copy file badge attributes to child elements - let parent handle all mouse events
                    // The mouse event handling in IncrementalBlockRenderer will traverse up the DOM to find file badge attributes
                }
            }
            pieces.addAll(fragment);

            lastEnd = range.end;
        }

        // Add remaining text after the last match as plain text (parent will handle clicks)
        if (lastEnd < text.length()) {
            String remainingText = text.substring(lastEnd);
            pieces.add(new TextNode(remainingText));
        }

        // Find parent badge before removing the text node
        Element parentBadge = findParentFileBadge(textNode);

        // Replace the original text node with all the pieces
        Node ref = textNode;
        for (Node n : pieces) {
            ref.after(n);
            ref = n;
        }
        textNode.remove();

        // Print the generated structure for debugging
        if (parentBadge != null) {
            System.out.println("=== FILE BADGE HIGHLIGHTING APPLIED ===");
            System.out.println("Search term: '" + searchTerm + "'");
            System.out.println("Original text: '" + text + "'");
            System.out.println("Matches found: " + ranges.size());
            System.out.println("Generated HTML: " + parentBadge.outerHtml());
            System.out.println("=== END FILE BADGE HIGHLIGHTING ===");
        }
    }

    /**
     * Apply highlights to normal text (not inside file badges).
     */
    private void applyHighlightsToNormalText(TextNode textNode, String text, List<MatchRange> ranges) {
        List<Node> pieces = new ArrayList<>();
        int lastEnd = 0;

        for (MatchRange range : ranges) {
            // Add text before this match
            if (range.start > lastEnd) {
                String beforeText = text.substring(lastEnd, range.start);
                pieces.add(new TextNode(beforeText));
            }

            // Add the highlighted match
            String matchText = text.substring(range.start, range.end);
            int markerId = ID_GEN.getAndIncrement();

            var snippetHtml = wrapperStart + matchText + wrapperEnd;
            var fragment = Jsoup.parseBodyFragment(snippetHtml).body().childNodes();
            for (Node fragNode : fragment) {
                if (fragNode instanceof Element fragEl) {
                    fragEl.attr(BROKK_MARKER_ATTR, "1");
                    fragEl.attr(BROKK_ID_ATTR, Integer.toString(markerId));
                    fragEl.addClass(SEARCH_MARKER_CLASS); // Add search marker class for cleanup
                }
            }
            pieces.addAll(fragment);

            lastEnd = range.end;
        }

        // Add remaining text after the last match
        if (lastEnd < text.length()) {
            String remainingText = text.substring(lastEnd);
            pieces.add(new TextNode(remainingText));
        }

        // Replace the original text node with all the pieces
        Node ref = textNode;
        for (Node n : pieces) {
            ref.after(n);
            ref = n;
        }
        textNode.remove();
    }

    /**
     * Checks if a text node is inside a file badge element.
     */
    private boolean isInsideFileBadge(TextNode textNode) {
        Node parent = textNode.parent();
        while (parent instanceof Element element) {
            if (element.hasClass("clickable-file-badge") ||
                (element.hasAttr("title") && element.attr("title").startsWith("file:"))) {
                return true;
            }
            parent = parent.parent();
        }
        return false;
    }

    /**
     * Finds the parent file badge element for debugging output.
     */
    private @Nullable Element findParentFileBadge(TextNode textNode) {
        Node parent = textNode.parent();
        while (parent instanceof Element element) {
            if (element.hasClass("clickable-file-badge") ||
                (element.hasAttr("title") && element.attr("title").startsWith("file:"))) {
                return element;
            }
            parent = parent.parent();
        }
        return null;
    }





    /**
     * Writes the complete HTML structure with embedded CSS styling to a file.
     * This shows the final DOM after search highlighting has been applied.
     * Only called when ENABLE_HTML_DEBUG_OUTPUT is true.
     */
    private void writeCompleteHtmlWithCss(Element root) {
        try {
            // Create a complete HTML document with embedded CSS
            StringBuilder htmlWithCss = new StringBuilder();
            htmlWithCss.append("<!DOCTYPE html>\n<html>\n<head>\n");
            htmlWithCss.append("<title>Search Results for '").append(searchTerm).append("'</title>\n");
            htmlWithCss.append("<style>\n");
            
            // Add the CSS rules that would be applied by MarkdownComponentData
            addCssRules(htmlWithCss);
            
            htmlWithCss.append("</style>\n</head>\n<body>\n");
            htmlWithCss.append("<h1>Search Results for '").append(searchTerm).append("'</h1>\n");
            htmlWithCss.append(root.html());
            htmlWithCss.append("\n</body>\n</html>");
            
            // Write to search.html file
            var htmlFile = java.nio.file.Path.of("search.html");
            java.nio.file.Files.writeString(htmlFile, htmlWithCss.toString());
            
            System.out.println("Search HTML written to: " + htmlFile.toAbsolutePath());
            
        } catch (Exception e) {
            logger.error("Failed to write search.html file", e);
            System.out.println("Error writing search HTML file: " + e.getMessage());
        }
    }
    
    /**
     * Adds the CSS rules that MarkdownComponentData would apply to the HTML.
     */
    private void addCssRules(StringBuilder css) {
        // Badge styling
        css.append(".badge {\n");
        css.append("  display: inline-block;\n");
        css.append("  padding: 0.15em 0.4em;\n");
        css.append("  margin-left: 0.25em;\n");
        css.append("  font-size: 75%;\n");
        css.append("  font-weight: 700;\n");
        css.append("  line-height: 1;\n");
        css.append("  text-align: center;\n");
        css.append("  white-space: nowrap;\n");
        css.append("  vertical-align: baseline;\n");
        css.append("  border-radius: 0.25rem;\n");
        css.append("}\n\n");
        
        css.append(".badge-symbol { background-color: #17a2b8; color: white; }\n");
        css.append(".badge-file { background-color: #28a745; color: white; }\n");
        css.append(".badge-class { background-color: #6610f2; color: white; }\n");
        css.append(".badge-function { background-color: #fd7e14; color: white; }\n");
        css.append(".badge-field { background-color: #20c997; color: white; }\n");
        css.append(".badge-module { background-color: #6f42c1; color: white; }\n\n");
        
        // File badge styling
        css.append(".clickable-file-badge {\n");
        css.append("  color: #0066cc;\n");
        css.append("  text-decoration: underline;\n");
        css.append("}\n\n");
        
        // Search highlighting - using similar colors to the actual implementation
        css.append(".brokk-search-highlight {\n");
        css.append("  background-color: #ffff00;\n");
        css.append("  color: black;\n");
        css.append("}\n\n");
        
        css.append(".brokk-search-current {\n");
        css.append("  background-color: #ff8c00;\n");
        css.append("  color: black;\n");
        css.append("}\n\n");
        
        css.append(".brokk-search-marker {\n");
        css.append("  background-color: #ffff00;\n");
        css.append("  color: black;\n");
        css.append("}\n\n");
        
        // Higher specificity rules for search highlights within badges
        css.append(".badge.brokk-search-highlight { background-color: #ffff00; color: black; }\n");
        css.append(".badge.brokk-search-current { background-color: #ff8c00; color: black; }\n");
        css.append(".clickable-file-badge .brokk-search-highlight { background-color: #ffff00; color: black; }\n");
        css.append(".clickable-file-badge .brokk-search-current { background-color: #ff8c00; color: black; }\n");
        css.append(".badge-file .brokk-search-highlight { background-color: #ffff00; color: black; }\n");
        css.append(".badge-file .brokk-search-current { background-color: #ff8c00; color: black; }\n\n");
        
        // Search highlights for file badge parts
        css.append(".clickable-file-badge .file-badge-part.brokk-search-highlight { background-color: #ffff00; color: black; }\n");
        css.append(".clickable-file-badge .file-badge-part.brokk-search-current { background-color: #ff8c00; color: black; }\n");
        css.append(".file-badge-part.brokk-search-highlight { background-color: #ffff00; color: black; }\n");
        css.append(".file-badge-part.brokk-search-current { background-color: #ff8c00; color: black; }\n");
    }

    @Override
    public int getCustomizerId() {
        return CUSTOMIZER_ID;
    }

}
