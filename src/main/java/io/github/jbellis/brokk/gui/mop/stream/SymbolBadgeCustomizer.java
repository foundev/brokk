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
    private static final boolean ENABLE_FILE_BADGES = false; // Disabled - filenames are clickable without badges
    
    private static final Pattern SYMBOL_PATTERN =
            Pattern.compile("[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*(?:\\.[a-z][A-Za-z0-9_]+\\(\\))?");

    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("[a-zA-Z0-9_.-]+/[a-zA-Z0-9_./+-]+\\.[a-zA-Z0-9]+|[a-zA-Z0-9_.-]+\\.[a-zA-Z0-9]+");

    private static final String BADGE_TYPE_SYMBOL = "symbol";
    private static final String BADGE_TYPE_FILE = "file";

    // Unique ID for this customizer type
    private static final int CUSTOMIZER_ID = 1001;

    // Global counter for unique badge IDs
    private static final AtomicInteger BADGE_ID_COUNTER = new AtomicInteger(0);

    private final IContextManager contextManager;
    private final io.github.jbellis.brokk.analyzer.IAnalyzer analyzer;
    private final boolean analyzerReady;

    private SymbolBadgeCustomizer(IContextManager contextManager, io.github.jbellis.brokk.analyzer.IAnalyzer analyzer, boolean analyzerReady) {
        this.contextManager = contextManager;
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

            // Check if a badge already exists to prevent double-injection
            if (!anchor.select("> .badge-symbol").isEmpty()) {
                logger.trace("[SymbolBadgeCustomizer] Skipping anchor, badge already exists for symbolId '{}': {}", symbolId, anchor.outerHtml());
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
                // Validate file exists in project
                if (isFileInProject(symbolId)) {
                    badge = createBadgeForFile(symbolId);
                } else {
                    logger.trace("[SymbolBadgeCustomizer] File '{}' not found in project, skipping badge", symbolId);
                    continue;
                }
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

        for (Element code : codeElements) {
            String codeText = code.text();
            String badgeType = null;
            Element badge = null;

            if (SYMBOL_PATTERN.matcher(codeText).matches()) {
                badgeType = BADGE_TYPE_SYMBOL;
            } else if (FILENAME_PATTERN.matcher(codeText).matches()) {
                badgeType = BADGE_TYPE_FILE;
            } else {
                continue;
            }

            // Skip if a badge already exists as the next sibling
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
                // Validate file exists in project
                if (isFileInProject(codeText)) {
                    badge = createBadgeForFile(codeText);
                } else {
                    logger.debug("[SymbolBadgeCustomizer] File '{}' not found in project, skipping badge", codeText);
                    continue;
                }
            }

            if (badge != null) {
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

    private boolean isFileInProject(String filename) {
        try {
            // Use the established file resolution pattern from EditBlock
            io.github.jbellis.brokk.EditBlock.resolveProjectFile(contextManager, filename);
            return true;
        } catch (io.github.jbellis.brokk.EditBlock.SymbolNotFoundException e) {
            logger.debug("[SymbolBadgeCustomizer] File '{}' not found in project: {}", filename, e.getMessage());
            return false;
        } catch (io.github.jbellis.brokk.EditBlock.SymbolAmbiguousException e) {
            // Ambiguous means multiple matches exist, so the file is in the project
            logger.debug("[SymbolBadgeCustomizer] File '{}' has multiple matches (treating as found): {}", filename, e.getMessage());
            return true;
        } catch (Exception e) {
            logger.debug("[SymbolBadgeCustomizer] Error checking file '{}': {}", filename, e.getMessage(), e);
            return false;
        }
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
        return new SymbolBadgeCustomizer(contextManager, analyzer, true);
    }

    @Override
    public int getCustomizerId() {
        return CUSTOMIZER_ID;
    }

    private Element createBadgeForFile(String filename) {
        int badgeId = BADGE_ID_COUNTER.incrementAndGet();
        // Encode the file information in the title attribute since Swing doesn't preserve data- attributes
        String encodedTitle = String.format("file:%s:id:%d", filename, badgeId);
        return new Element("span")
                .addClass("badge")
                .addClass("badge-symbol")
                .addClass("badge-file")
                .addClass("clickable-badge")
                .text("F")
                .attr("title", encodedTitle);
    }
}
