package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.IContextManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.file.Path;
import java.util.Locale;
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
 */
public final class SymbolBadgeCustomizer implements HtmlCustomizer {
    private static final Logger logger = LogManager.getLogger(SymbolBadgeCustomizer.class);
    
    // Compilation flags to enable/disable specific badge types
    private static final boolean ENABLE_SYMBOL_BADGES = true;
    private static final boolean ENABLE_FILE_BADGES = true; // Re-enabled - badges with icons
    
    private static final Pattern SYMBOL_PATTERN =
            Pattern.compile("[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*(?:\\.[a-z][A-Za-z0-9_]+\\(\\))?");

    // More flexible pattern that matches common file extensions
    private static final Pattern FILENAME_PATTERN =
            Pattern.compile(".*\\.(java|kt|scala|py|js|ts|jsx|tsx|cpp|c|h|hpp|cc|cxx|go|rs|rb|php|cs|swift|dart|vue|xml|json|yaml|yml|properties|md|txt|html|css|scss|sql|sh|gradle|xml)$");

    private static final String BADGE_TYPE_SYMBOL = "symbol";
    private static final String BADGE_TYPE_FILE = "file";

    // Unique ID for this customizer type
    private static final int CUSTOMIZER_ID = 1001;

    // Global counter for unique badge IDs
    private static final AtomicInteger BADGE_ID_COUNTER = new AtomicInteger(0);

    private final io.github.jbellis.brokk.analyzer.IAnalyzer analyzer;
    private final boolean analyzerReady;

    private SymbolBadgeCustomizer(io.github.jbellis.brokk.analyzer.IAnalyzer analyzer, boolean analyzerReady) {
        this.analyzer = analyzer;
        this.analyzerReady = analyzerReady;
    }

    @Override
    public void customize(Element root) {
        if (root == null) {
            return;
        }

        if (!analyzerReady) {
            logger.debug("[SymbolBadgeCustomizer] Analyzer not ready, skipping badge customization");
            return;
        }
        
        logger.debug("[SymbolBadgeCustomizer] Starting customization. ENABLE_FILE_BADGES={}, ENABLE_SYMBOL_BADGES={}", 
                    ENABLE_FILE_BADGES, ENABLE_SYMBOL_BADGES);
        
        Elements anchors = root.select("a");
        int anchorsProcessed = 0;
        int badgesInjected = 0;

        if (!anchors.isEmpty()) {
            logger.debug("[SymbolBadgeCustomizer] Found {} anchor(s) to process.", anchors.size());
        }

        for (Element anchor : anchors) {
            anchorsProcessed++;
            String symbolId = anchor.attr("data-symbol-id");
            String badgeType = null;

            if (symbolId.isBlank()) {
                String candidate = anchor.text();
                if (SYMBOL_PATTERN.matcher(candidate).matches()) {
                    symbolId = candidate;
                    badgeType = BADGE_TYPE_SYMBOL;
                    // write back so other customizers can rely on it later
                    anchor.attr("data-symbol-id", symbolId);
                } else if (FILENAME_PATTERN.matcher(candidate).matches()) {
                    symbolId = candidate;
                    badgeType = BADGE_TYPE_FILE;
                    anchor.attr("data-file-id", symbolId);
                }
            } else {
                badgeType = BADGE_TYPE_SYMBOL;
            }

            if (symbolId.isBlank()) {
                // not a symbol or file anchor
                continue;
            }

            // Check if this element is already clickable to prevent double-processing
            if (anchor.hasClass("clickable-file-badge") || !anchor.select("> .badge-symbol").isEmpty()) {
                logger.trace("[SymbolBadgeCustomizer] Skipping anchor, already processed for symbolId '{}': {}", symbolId, anchor.outerHtml());
                continue;
            }

            Element badge = null;
            if (BADGE_TYPE_SYMBOL.equals(badgeType) && ENABLE_SYMBOL_BADGES) {
                // Validate symbol exists in project
                var definition = analyzer.getDefinition(symbolId);
                if (definition.isEmpty()) {
                    logger.trace("[SymbolBadgeCustomizer] Symbol '{}' not found in project, skipping badge", symbolId);
                    continue;
                }
                badge = createBadgeForSymbol(definition.get());
            } else if (BADGE_TYPE_FILE.equals(badgeType) && ENABLE_FILE_BADGES) {
                // Replace the anchor content with clickable filename badge
                replaceWithClickableFilenameBadge(anchor, symbolId);
                logger.debug("[SymbolBadgeCustomizer] Replaced anchor with clickable filename badge for '{}'", symbolId);
                badgesInjected++;
                continue; // Skip the normal badge creation since we replaced the element
            }

            if (badge == null) {
                continue;
            }
            anchor.appendChild(badge);
            badgesInjected++;
        }

        // Process inline <code> elements within <p> tags
        Elements codeElements = root.select("p code");
        if (!codeElements.isEmpty()) {
            logger.debug("[SymbolBadgeCustomizer] Found {} 'p code' element(s).", codeElements.size());
        }
        
        // Also check for code elements in other contexts
        Elements allCodeElements = root.select("code");
        logger.debug("[SymbolBadgeCustomizer] Total code elements found: {}", allCodeElements.size());

        // Process all code elements, not just those in <p> tags
        for (Element code : allCodeElements) {
            String codeText = code.text();
            String badgeType = null;
            Element badge = null;

            if (SYMBOL_PATTERN.matcher(codeText).matches()) {
                badgeType = BADGE_TYPE_SYMBOL;
            } else if (FILENAME_PATTERN.matcher(codeText).matches()) {
                badgeType = BADGE_TYPE_FILE;
                logger.debug("[SymbolBadgeCustomizer] Found filename in code element: '{}'", codeText);
            } else {
                continue;
            }

            // Skip if this code element is already clickable or has a badge
            if (code.hasClass("clickable-file-badge")) {
                logger.trace("[SymbolBadgeCustomizer] Skipping code element, already clickable for text '{}': {}", codeText, code.outerHtml());
                continue;
            }
            Element nextSibling = code.nextElementSibling();
            if (nextSibling != null && nextSibling.hasClass("badge-symbol")) {
                logger.trace("[SymbolBadgeCustomizer] Skipping code element, badge already exists for text '{}': {}", codeText, code.outerHtml());
                continue;
            }

            if (BADGE_TYPE_SYMBOL.equals(badgeType) && ENABLE_SYMBOL_BADGES) {
                // Validate symbol exists in project
                var definition = analyzer.getDefinition(codeText);
                if (definition.isEmpty()) {
                    logger.trace("[SymbolBadgeCustomizer] Symbol '{}' not found in project, skipping badge", codeText);
                    continue;
                }
                badge = createBadgeForSymbol(definition.get());
            } else if (BADGE_TYPE_FILE.equals(badgeType) && ENABLE_FILE_BADGES) {
                // Replace the code element content with clickable filename badge
                replaceWithClickableFilenameBadge(code, codeText);
                logger.debug("[SymbolBadgeCustomizer] Replaced code element with clickable filename badge for '{}'", codeText);
                badgesInjected++;
                continue; // Skip the normal badge creation since we replaced the element
            }

            if (badge != null) {
                code.after(badge);
                badgesInjected++;
                logger.debug("[SymbolBadgeCustomizer] Injected badge after code element: {}", badge.outerHtml());
            }
        }

        if (badgesInjected > 0 || anchorsProcessed > 0) {
            logger.debug("[SymbolBadgeCustomizer] processed {} anchor(s), injected {} badges (anchors+code)", anchorsProcessed, badgesInjected);
        }
    }

    private Element createBadgeForSymbol(io.github.jbellis.brokk.analyzer.CodeUnit symbol) {
        String badgeText = getBadgeText(symbol);
        String badgeClass = getBadgeClass(symbol);

        return new Element("span")
                .addClass("badge")
                .addClass("badge-symbol")
                .addClass(badgeClass)
                .text(badgeText)
                .attr("title", String.format("%s %s (%s)",
                      symbol.kind().name().toLowerCase(Locale.ROOT),
                      symbol.fqName(),
                      symbol.source().toString()));
    }

    private String getBadgeText(io.github.jbellis.brokk.analyzer.CodeUnit symbol) {
        return switch (symbol.kind()) {
            case CLASS -> "C";
            case FUNCTION -> "F";
            case FIELD -> "V";
            case MODULE -> "M";
        };
    }

    private String getBadgeClass(io.github.jbellis.brokk.analyzer.CodeUnit symbol) {
        return "badge-" + symbol.kind().name().toLowerCase(Locale.ROOT);
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
        return new SymbolBadgeCustomizer(analyzer, true);
    }

    @Override
    public int getCustomizerId() {
        return CUSTOMIZER_ID;
    }

    private void replaceWithClickableFilenameBadge(Element element, String filename) {
        int badgeId = BADGE_ID_COUNTER.incrementAndGet();
        // Encode the file information in the title attribute since Swing doesn't preserve data- attributes
        String encodedTitle = String.format("file:%s:id:%d", filename, badgeId);
        
        // Clear the element and replace with clickable filename badge content
        element.empty();
        element.addClass("clickable-file-badge")
               .text(filename)
               .attr("title", encodedTitle)
               .attr("style", "cursor: pointer; text-decoration: underline;");
    }
}
