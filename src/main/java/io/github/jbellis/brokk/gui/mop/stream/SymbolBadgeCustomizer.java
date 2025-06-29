package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.util.PatternConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decorates <a> tags that carry a data-symbol-id attribute (added by BrokkMarkdownExtension)
 * with a placeholder badge.
 *
 * This is a stateless customizer that receives analyzer state during the customize() call.
 * Integration is intentionally left manual: callers must register an instance
 * via {@link IncrementalBlockRenderer#setHtmlCustomizer(HtmlCustomizer)}.  This
 * avoids impacting existing behaviour while the feature is developed.
 *
 * <h3>Configuration</h3>
 * Badge generation can be controlled via system properties:
 * <ul>
 *   <li>{@code brokk.badges.symbols} - Enable/disable symbol badges (default: true)</li>
 *   <li>{@code brokk.badges.files} - Enable/disable file reference badges (default: true)</li>
 * </ul>
 *
 * Example: {@code -Dbrokk.badges.files=false} to disable file badges
 */
public final class SymbolBadgeCustomizer implements HtmlCustomizer {

    // Configuration flags to enable/disable specific badge types (configurable via system properties)
    // Default to true for symbol badges, devmode setting for file badges
    private static final String DEFAULT_BADGE_SETTING = Boolean.toString(Boolean.getBoolean("brokk.devmode"));
    private static final boolean ENABLE_SYMBOL_BADGES = Boolean.parseBoolean(
        System.getProperty("brokk.badges.symbols", "true"));
    private static final boolean ENABLE_FILE_BADGES = Boolean.parseBoolean(
        System.getProperty("brokk.badges.files", DEFAULT_BADGE_SETTING));

    // Use centralized patterns from PatternConstants to avoid duplication

    private static final String BADGE_TYPE_SYMBOL = "symbol";
    private static final String BADGE_TYPE_FILE = "file";

    // HTML element tag names
    private static final String TAG_ANCHOR = "a";
    private static final String TAG_CODE = "code";

    // Unique ID for this customizer type
    private static final int CUSTOMIZER_ID = 1001;

    // Local counter for unique badge IDs within this customizer instance
    private final AtomicInteger badgeIdCounter = new AtomicInteger(0);

    private final IAnalyzer analyzer;
    private final boolean analyzerReady;

    private SymbolBadgeCustomizer(IAnalyzer analyzer, boolean analyzerReady) {
        this.analyzer = analyzer;
        this.analyzerReady = analyzerReady;
    }

    @Override
    public void customize(Element root) {
        if (!analyzerReady) {
            return;
        }

        traverseAndProcess(root);
    }

    /**
     * Single traversal of DOM tree to process all relevant elements.
     */
    private void traverseAndProcess(Element element) {
        // Process current element if it's relevant
        if (TAG_ANCHOR.equals(element.tagName())) {
            processAnchorElement(element);
        } else if (TAG_CODE.equals(element.tagName())) {
            processCodeElement(element);
        }

        // Recursively process children
        for (Element child : element.children()) {
            traverseAndProcess(child);
        }
    }


    /**
     * Process anchor elements for symbol/file badges.
     */
    private void processAnchorElement(Element anchor) {
        String symbolId = anchor.attr(BadgeConstants.ATTR_DATA_SYMBOL_ID);
        String badgeType = null;

        if (symbolId.isBlank()) {
            String candidate = anchor.text();
            if (PatternConstants.isSymbolLike(candidate)) {
                symbolId = candidate;
                badgeType = BADGE_TYPE_SYMBOL;
                // write back so other customizers can rely on it later
                anchor.attr(BadgeConstants.ATTR_DATA_SYMBOL_ID, symbolId);
            } else if (PatternConstants.isRecognizedFile(candidate)) {
                symbolId = candidate;
                badgeType = BADGE_TYPE_FILE;
                anchor.attr(BadgeConstants.ATTR_DATA_FILE_ID, symbolId);
            }
        } else {
            badgeType = BADGE_TYPE_SYMBOL;
        }

        if (symbolId.isBlank()) {
            // not a symbol or file anchor
            return;
        }

        // Check if this element is already clickable to prevent double-processing
        if (anchor.hasClass(BadgeConstants.CLASS_CLICKABLE_FILE_BADGE) || !anchor.select(BadgeConstants.SELECTOR_BADGE_SYMBOL).isEmpty()) {
            return;
        }

        if (BADGE_TYPE_SYMBOL.equals(badgeType) && ENABLE_SYMBOL_BADGES) {
            // First try exact definition lookup
            var definition = analyzer.getDefinition(symbolId);

            if (definition.isEmpty()) {
                // If exact lookup fails, try searching for matches
                var searchResults = analyzer.searchDefinitions(symbolId);

                if (!searchResults.isEmpty()) {
                    // Find the best match - prefer exact simple name matches
                    final String finalSymbolId = symbolId;
                    var bestMatch = searchResults.stream()
                        .filter(cu -> cu.shortName().equals(finalSymbolId) || cu.identifier().equals(finalSymbolId))
                        .findFirst()
                        .orElse(searchResults.get(0)); // Fallback to first result

                    // Replace the anchor content with clickable symbol badge
                    replaceWithClickableSymbolBadge(anchor, symbolId, bestMatch);
                    return;
                } else {
                    return;
                }
            } else {
                // Replace the anchor content with clickable symbol badge
                replaceWithClickableSymbolBadge(anchor, symbolId, definition.get());
                return;
            }
        } else if (BADGE_TYPE_FILE.equals(badgeType) && ENABLE_FILE_BADGES) {
            // Replace the anchor content with clickable filename badge
            replaceWithClickableFilenameBadge(anchor, symbolId);
            return;
        }

    }

    /**
     * Process code elements for symbol/file badges.
     */
    private void processCodeElement(Element code) {
        String codeText = code.text();
        String badgeType = null;

        if (PatternConstants.isSymbolLike(codeText)) {
            badgeType = BADGE_TYPE_SYMBOL;
        } else if (PatternConstants.isRecognizedFile(codeText)) {
            badgeType = BADGE_TYPE_FILE;
        } else {
            return;
        }

        // Skip if this code element is already clickable or has a badge
        if (code.hasClass(BadgeConstants.CLASS_CLICKABLE_FILE_BADGE)) {
            return;
        }
        Element nextSibling = code.nextElementSibling();
        if (nextSibling != null && nextSibling.hasClass(BadgeConstants.CLASS_BADGE_SYMBOL)) {
            return;
        }

        if (BADGE_TYPE_SYMBOL.equals(badgeType) && ENABLE_SYMBOL_BADGES) {
            // First try exact definition lookup
            var definition = analyzer.getDefinition(codeText);

            if (definition.isEmpty()) {
                // If exact lookup fails, try searching for matches
                var searchResults = analyzer.searchDefinitions(codeText);

                if (!searchResults.isEmpty()) {
                    // Find the best match - prefer exact simple name matches
                    final String finalCodeText = codeText;
                    var bestMatch = searchResults.stream()
                        .filter(cu -> cu.shortName().equals(finalCodeText) || cu.identifier().equals(finalCodeText))
                        .findFirst()
                        .orElse(searchResults.get(0)); // Fallback to first result

                    // Replace the code element content with clickable symbol badge
                    replaceWithClickableSymbolBadge(code, codeText, bestMatch);
                    return;
                } else {
                    return;
                }
            } else {
                // Replace the code element content with clickable symbol badge
                replaceWithClickableSymbolBadge(code, codeText, definition.get());
                return;
            }
        } else if (BADGE_TYPE_FILE.equals(badgeType) && ENABLE_FILE_BADGES) {
            // Replace the code element content with clickable filename badge
            replaceWithClickableFilenameBadge(code, codeText);
            return;
        }

    }


    private String getBadgeClass(CodeUnit symbol) {
        return BadgeConstants.BADGE_CLASS_PREFIX + symbol.kind().name().toLowerCase(Locale.ROOT);
    }


    /**
     * Factory method to create a customizer with current analyzer state.
     * Returns a no-op customizer if the analyzer is not ready.
     */
    public static HtmlCustomizer create(IContextManager contextManager) {

        if (contextManager == null) {
            return HtmlCustomizer.DEFAULT;
        }

        var analyzerWrapper = contextManager.getAnalyzerWrapper();
        if (analyzerWrapper == null) {
            return HtmlCustomizer.DEFAULT;
        }

        boolean isReady = analyzerWrapper.isReady();

        if (!isReady) {
            return HtmlCustomizer.DEFAULT;
        }

        var analyzer = analyzerWrapper.getNonBlocking();
        return new SymbolBadgeCustomizer(Objects.requireNonNull(analyzer), true);
    }

    @Override
    public int getCustomizerId() {
        return CUSTOMIZER_ID;
    }

    private void replaceWithClickableFilenameBadge(Element element, String filename) {
        int badgeId = badgeIdCounter.incrementAndGet();
        // Store encoded badge info in data attribute for internal processing
        String encodedBadgeInfo = String.format(BadgeConstants.TITLE_FORMAT, filename, badgeId);

        // Set user-friendly title showing just the filename (relative path)
        String userFriendlyTitle = Path.of(filename).getFileName().toString();

        // Use lighter blue color for file badges to match CSS styling
        String inlineStyle = BadgeConstants.STYLE_CLICKABLE + " color: #7ba7d4;";

        // PRESERVE existing content (including search highlights) instead of clearing
        // Only add styling and attributes to make it a clickable badge
        element.addClass(BadgeConstants.CLASS_BADGE)
               .addClass(BadgeConstants.CLASS_BADGE_SYMBOL)
               .addClass(BadgeConstants.CLASS_BADGE_FILE)
               .addClass(BadgeConstants.CLASS_CLICKABLE_BADGE)
               .addClass(BadgeConstants.CLASS_CLICKABLE_FILE_BADGE) // Also set via CSS for HTML debug output
               .attr(BadgeConstants.ATTR_DATA_BADGE_INFO, encodedBadgeInfo) // Encoded data for processing
               .attr(BadgeConstants.ATTR_TITLE, userFriendlyTitle) // User-friendly file path for display
               .attr(BadgeConstants.ATTR_STYLE, inlineStyle); // Inline style takes precedence in Swing

        // If element has no content, set the filename text
        if (element.text().trim().isEmpty()) {
            element.text(filename);
        }
    }

    private void replaceWithClickableSymbolBadge(Element element, String symbolName, CodeUnit codeUnit) {
        int badgeId = badgeIdCounter.incrementAndGet();
        // Store encoded symbol ID for click handler processing
        String encodedSymbolId = symbolName + ":" + badgeId;

        // Create user-friendly title with symbol info
        String symbolType = getSymbolTypeDisplay(codeUnit);
        String userFriendlyTitle = symbolType + " " + codeUnit.fqName() + " (" + codeUnit.source().toString() + ")";

        // Green color for symbol badges
        String inlineStyle = BadgeConstants.STYLE_CLICKABLE + " color: #28a745;";

        // PRESERVE existing content (including search highlights) instead of clearing
        // Only add styling and attributes to make it a clickable symbol badge
        element.addClass(BadgeConstants.CLASS_BADGE)
               .addClass(BadgeConstants.CLASS_BADGE_SYMBOL)
               .addClass(getBadgeClass(codeUnit))
               .addClass(BadgeConstants.CLASS_CLICKABLE_BADGE)
               .attr(BadgeConstants.ATTR_DATA_SYMBOL_ID, encodedSymbolId) // Symbol ID for processing
               .attr(BadgeConstants.ATTR_TITLE, userFriendlyTitle) // User-friendly symbol info for display
               .attr(BadgeConstants.ATTR_STYLE, inlineStyle); // Red color styling

        // If element has no content, set the symbol name text
        if (element.text().trim().isEmpty()) {
            element.text(symbolName);
        }
    }

    private String getSymbolTypeDisplay(CodeUnit codeUnit) {
        return switch (codeUnit.kind()) {
            case CLASS -> "class";
            case FUNCTION -> "function";
            case FIELD -> "field";
            case MODULE -> "module";
        };
    }

}
