package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.gui.mop.stream.BadgeConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Element;

import java.nio.file.Path;

public final class HtmlUtil {
    private static final Logger logger = LogManager.getLogger(HtmlUtil.class);

    public static final boolean ENABLE_HTML_DEBUG_OUTPUT = true;

    private HtmlUtil() {}

    /**
     * Converts HTML content to Markdown.
     * <p>
     * NOTE: This is currently a placeholder implementation.
     *
     * @param htmlContent The HTML content to convert.
     * @return A Markdown representation of the HTML content.
     */
    public static String convertToMarkdown(String htmlContent) {
        // Placeholder implementation
        return "<!-- HTML Content (conversion to Markdown pending) -->\n" + htmlContent;
    }

    public static void writeSearchHtml(String searchTerm, Element root) {
        if (!ENABLE_HTML_DEBUG_OUTPUT) {
            return;
        }

        var title = "Search Results for '" + searchTerm + "'";
        var content = "<h1>" + title + "</h1>\n" + root.html();
        writeHtmlWithCss("search.html", title, content);
    }

    public static void writeInitialRenderHtml(String content) {
        if (!ENABLE_HTML_DEBUG_OUTPUT) {
            return;
        }

        var title = "Initial Render Debug - Looking for File Badges";
        var htmlContent = "<h1>" + title + "</h1>\n" + content;
        writeHtmlWithCss("initial-render.html", title, htmlContent);
    }

    private static void writeHtmlWithCss(String filename, String title, String content) {
        var html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html>\n<head>\n");
        html.append("<title>").append(title).append("</title>\n");
        html.append("<style>\n");
        addCssRules(html);
        html.append("</style>\n</head>\n<body>\n");
        html.append(content);
        html.append("\n</body>\n</html>");

        try {
            var htmlFile = Path.of(filename);
            AtomicWrites.atomicOverwrite(htmlFile, html.toString());
        } catch (Exception e) {
            logger.warn("Failed to write HTML debug file {}: {}", filename, e.getMessage());
        }
    }

    private static void addCssRules(StringBuilder css) {
        css.append(".search-highlight { background-color: yellow; font-weight: bold; }\n");
        css.append(".file-badge { background: #007bff; color: white; padding: 2px 6px; border-radius: 3px; }\n");
        css.append(BadgeConstants.SELECTOR_CLICKABLE_FILE_BADGE + " { color: #0066cc; " + BadgeConstants.STYLE_TEXT_DECORATION_UNDERLINE + "; border: 2px solid red; }\n");
        css.append(".badge { background: #28a745; color: white; padding: 2px 6px; }\n");
        css.append("body { font-family: Arial, sans-serif; margin: 20px; }\n");
        css.append("h1 { color: #333; }\n");
    }
}
