package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

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
    // Default to devmode setting for development convenience
    private static final String DEFAULT_BADGE_SETTING = Boolean.toString(Boolean.getBoolean("brokk.devmode"));
    private static final boolean ENABLE_SYMBOL_BADGES = Boolean.parseBoolean(
        System.getProperty("brokk.badges.symbols", DEFAULT_BADGE_SETTING));
    private static final boolean ENABLE_FILE_BADGES = Boolean.parseBoolean(
        System.getProperty("brokk.badges.files", DEFAULT_BADGE_SETTING));

    private static final Pattern SYMBOL_PATTERN =
            Pattern.compile("[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*(?:\\.[a-z][A-Za-z0-9_]+\\(\\))?");

    // More flexible pattern that matches common file extensions
    private static final Pattern FILENAME_PATTERN =
            Pattern.compile(".*\\.(java|kt|scala|py|js|ts|jsx|tsx|cpp|c|h|hpp|cc|cxx|go|rs|rb|php|cs|swift|dart|vue|xml|json|yaml|yml|properties|md|txt|html|css|scss|sql|sh|gradle|xml)$");

    private static final String BADGE_TYPE_SYMBOL = "symbol";
    private static final String BADGE_TYPE_FILE = "file";
    
    // HTML element tag names
    private static final String TAG_ANCHOR = "a";
    private static final String TAG_CODE = "code";
    private static final String TAG_SPAN = "span";

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
            if (SYMBOL_PATTERN.matcher(candidate).matches()) {
                symbolId = candidate;
                badgeType = BADGE_TYPE_SYMBOL;
                // write back so other customizers can rely on it later
                anchor.attr(BadgeConstants.ATTR_DATA_SYMBOL_ID, symbolId);
            } else if (FILENAME_PATTERN.matcher(candidate).matches()) {
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

        Element badge = null;
        if (BADGE_TYPE_SYMBOL.equals(badgeType) && ENABLE_SYMBOL_BADGES) {
            // Validate symbol exists in project
            var definition = analyzer.getDefinition(symbolId);
            if (definition.isEmpty()) {
                return;
            }
            badge = createBadgeForSymbol(definition.get());
        } else if (BADGE_TYPE_FILE.equals(badgeType) && ENABLE_FILE_BADGES) {
            // Replace the anchor content with clickable filename badge
            replaceWithClickableFilenameBadge(anchor, symbolId);
            return;
        }

        if (badge != null) {
            anchor.appendChild(badge);
        }
    }

    /**
     * Process code elements for symbol/file badges.
     */
    private void processCodeElement(Element code) {
        String codeText = code.text();
        String badgeType = null;
        Element badge = null;

        if (SYMBOL_PATTERN.matcher(codeText).matches()) {
            badgeType = BADGE_TYPE_SYMBOL;
        } else if (FILENAME_PATTERN.matcher(codeText).matches()) {
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
            // Validate symbol exists in project
            var definition = analyzer.getDefinition(codeText);
            if (definition.isEmpty()) {
                return;
            }
            badge = createBadgeForSymbol(definition.get());
        } else if (BADGE_TYPE_FILE.equals(badgeType) && ENABLE_FILE_BADGES) {
            // Replace the code element content with clickable filename badge
            replaceWithClickableFilenameBadge(code, codeText);
            return;
        }

        if (badge != null) {
            code.after(badge);
        }
    }

    private Element createBadgeForSymbol(CodeUnit symbol) {
        String badgeText = getBadgeText(symbol);
        String badgeClass = getBadgeClass(symbol);

        return new Element(TAG_SPAN)
                .addClass(BadgeConstants.CLASS_BADGE)
                .addClass(BadgeConstants.CLASS_BADGE_SYMBOL)
                .addClass(badgeClass)
                .text(badgeText)
                .attr(BadgeConstants.ATTR_TITLE, String.format(BadgeConstants.BADGE_TITLE_FORMAT,
                      symbol.kind().name().toLowerCase(Locale.ROOT),
                      symbol.fqName(), symbol.source()));
    }

    private String getBadgeText(CodeUnit symbol) {
        return switch (symbol.kind()) {
            case CLASS -> BadgeConstants.BADGE_TEXT_CLASS;
            case FUNCTION -> BadgeConstants.BADGE_TEXT_FUNCTION;
            case FIELD -> BadgeConstants.BADGE_TEXT_FIELD;
            case MODULE -> BadgeConstants.BADGE_TEXT_MODULE;
        };
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
        if (analyzerWrapper == null || !analyzerWrapper.isReady()) {
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

        // Get theme-appropriate link color for inline styling (required for Swing)
        boolean isDarkTheme = isDarkTheme();
        String linkColor = ThemeColors.getColorHex(isDarkTheme, "link_color_hex");
        String inlineStyle = BadgeConstants.STYLE_CLICKABLE + " color: " + linkColor + ";";

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

    /**
     * Determines if the current theme is dark mode.
     * @return true if dark theme is active, false otherwise
     */
    private boolean isDarkTheme() {
        String currentTheme = MainProject.getTheme();
        return GuiTheme.THEME_DARK.equalsIgnoreCase(currentTheme);
    }
}
