package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.IContextManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.file.Path;
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

    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("[a-zA-Z0-9_.-]+/[a-zA-Z0-9_./+-]+\\.[a-zA-Z0-9]+|[a-zA-Z0-9_.-]+\\.[a-zA-Z0-9]+");

    private static final String BADGE_TYPE_SYMBOL = "symbol";
    private static final String BADGE_TYPE_FILE = "file";

    private final IContextManager contextManager;

    public SymbolBadgeCustomizer(IContextManager contextManager) {
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
            if (BADGE_TYPE_SYMBOL.equals(badgeType)) {
                // Validate symbol exists in project
                var definition = analyzer.getDefinition(symbolId);
                if (definition.isEmpty()) {
                    logger.trace("[SymbolBadgeCustomizer] Symbol '{}' not found in project, skipping badge", symbolId);
                    continue;
                }
                badge = createBadgeForSymbol(definition.get());
            } else if (BADGE_TYPE_FILE.equals(badgeType)) {
                System.out.println("Checking if file exists in project: " + symbolId);
                // Validate file exists in project
                if (isFileInProject(symbolId)) {
                    badge = createBadgeForFile(symbolId);
                    System.out.println("detect file " +symbolId);
                    System.out.println(badge);
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
                System.out.println("Code text (symbol) " + codeText);
            } else if (FILENAME_PATTERN.matcher(codeText).matches()) {
                badgeType = BADGE_TYPE_FILE;
                System.out.println("Code text (file) " + codeText);
            } else {
                continue;
            }

            // Skip if a badge already exists as the next sibling
            Element nextSibling = code.nextElementSibling();
            if (nextSibling != null && nextSibling.hasClass("badge-symbol")) {
                logger.trace("[SymbolBadgeCustomizer] Skipping code element, badge already exists for text '{}': {}", codeText, code.outerHtml());
                continue;
            }

            if (BADGE_TYPE_SYMBOL.equals(badgeType)) {
                // Validate symbol exists in project
                var definition = analyzer.getDefinition(codeText);
                if (definition.isEmpty()) {
                    logger.trace("[SymbolBadgeCustomizer] Symbol '{}' not found in project, skipping badge", codeText);
                    continue;
                }
                badge = createBadgeForSymbol(definition.get());
            } else if (BADGE_TYPE_FILE.equals(badgeType)) {
                // Validate file exists in project
                if (isFileInProject(codeText)) {

                    badge = createBadgeForFile(codeText);
                } else {
                    logger.trace("[SymbolBadgeCustomizer] File '{}' not found in project, skipping badge", codeText);
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
            // First try the standard file resolution pattern
            var file = contextManager.toFile(filename);
            System.out.println("Checking file: " + filename);
            System.out.println("  -> ProjectFile: " + file);
            System.out.println("  -> absPath: " + file.absPath());
            System.out.println("  -> exists(): " + file.exists());
            
            // Check if file exists AND is actually in the project's file list
            if (file.exists()) {
                var allFiles = contextManager.getProject().getAllFiles();
                boolean inProject = allFiles.contains(file);
                System.out.println("  -> in project: " + inProject);
                return inProject;
            }
            
            // If file doesn't exist physically, check if it's in the project file list anyway
            var allFiles = contextManager.getProject().getAllFiles();
            boolean inProjectList = allFiles.contains(file);
            System.out.println("  -> in project list (despite not existing): " + inProjectList);
            
            return inProjectList;
        } catch (Exception e) {
            System.out.println("  -> Exception: " + e.getMessage());
            logger.trace("[SymbolBadgeCustomizer] Error checking file '{}': {}", filename, e.getMessage());
            return false;
        }
    }

    private Element createBadgeForFile(String filename) {
        return new Element("span")
                .addClass("badge")
                .addClass("badge-symbol")
                .addClass("badge-file")
                .text("F")
                .attr("title", String.format("file %s", filename));
    }
}
