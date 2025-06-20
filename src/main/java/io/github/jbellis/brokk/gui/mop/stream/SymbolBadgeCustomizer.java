package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.ContextManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decorates <a> tags that carry a data-symbol-id attribute (added by BrokkMarkdownExtension)
 * with a placeholder badge.
 *
 * Integration is intentionally left manual: callers must register an instance
 * via {@link IncrementalBlockRenderer#setHtmlCustomizer(HtmlCustomizer)}.  This
 * avoids impacting existing behaviour while the feature is developed.
 */
public final class SymbolBadgeCustomizer implements HtmlCustomizer {
    private static final Logger logger = LogManager.getLogger(SymbolBadgeCustomizer.class);
    private static final Pattern SYMBOL_PATTERN =
            Pattern.compile("[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*(?:\\.[a-z][A-Za-z0-9_]+\\(\\))?");
    
    private final ContextManager contextManager;
    
    public SymbolBadgeCustomizer(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public void customize(Element root) {
        if (root == null) {
            return;
        }
        
        var analyzerWrapper = contextManager.getAnalyzerWrapper();
        if (!analyzerWrapper.isReady()) {
            logger.debug("[SymbolBadgeCustomizer] Analyzer not ready, skipping badge customization");
            return;
        }
        
        var analyzer = analyzerWrapper.getNonBlocking();
        Elements anchors = root.select("a");
        int anchorsProcessed = 0;
        int badgesInjected = 0;

        if (!anchors.isEmpty()) {
            logger.debug("[SymbolBadgeCustomizer] Found {} anchor(s) to process.", anchors.size());
        }

        for (Element anchor : anchors) {
            anchorsProcessed++;
            String symbolId = anchor.attr("data-symbol-id");
            if (symbolId == null || symbolId.isBlank()) {
                String candidate = anchor.text();
                if (SYMBOL_PATTERN.matcher(candidate).matches()) {
                    symbolId = candidate;
                    // write back so other customizers can rely on it later
                    anchor.attr("data-symbol-id", symbolId);
                }
            }

            if (symbolId == null || symbolId.isBlank()) {
                // not a symbol anchor
                continue;
            }

            // Check if a badge already exists to prevent double-injection
            if (!anchor.select("> .badge-symbol").isEmpty()) {
                logger.trace("[SymbolBadgeCustomizer] Skipping anchor, badge already exists for symbolId '{}': {}", symbolId, anchor.outerHtml());
                continue;
            }

            // Validate symbol exists in project
            var definition = analyzer.getDefinition(symbolId);
            if (definition.isEmpty()) {
                logger.trace("[SymbolBadgeCustomizer] Symbol '{}' not found in project, skipping badge", symbolId);
                continue;
            }

            Element badge = createBadgeForSymbol(definition.get());
            anchor.appendChild(badge);
            badgesInjected++;
        }

        // Process inline <code> elements within <p> tags
        Elements codeElements = root.select("p code");
        if (!codeElements.isEmpty()) {
            logger.debug("[SymbolBadgeCustomizer] Found {} 'p code' element(s).", codeElements.size());
        }

        for (Element code : codeElements) {
            String codeText = code.text();
            if (SYMBOL_PATTERN.matcher(codeText).matches()) {
                // Skip if a badge already exists as the next sibling
                Element nextSibling = code.nextElementSibling();
                if (nextSibling != null && nextSibling.hasClass("badge-symbol")) {
                    logger.trace("[SymbolBadgeCustomizer] Skipping code element, badge already exists for symbol '{}': {}", codeText, code.outerHtml());
                    continue;
                }

                // Validate symbol exists in project
                var definition = analyzer.getDefinition(codeText);
                if (definition.isEmpty()) {
                    logger.trace("[SymbolBadgeCustomizer] Symbol '{}' not found in project, skipping badge", codeText);
                    continue;
                }

                Element badge = createBadgeForSymbol(definition.get());
                code.after(badge);
                badgesInjected++;
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
}
