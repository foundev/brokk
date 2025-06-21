package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.gui.search.SearchConstants;
import io.github.jbellis.brokk.difftool.utils.Colors;
import io.github.jbellis.brokk.difftool.utils.ColorUtil;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;

/**
 * Represents a Markdown prose segment between placeholders.
 */
public record MarkdownComponentData(int id, String html) implements ComponentData {
    @Override
    public String fp() {
        return html.hashCode() + "";
    }
    
    @Override
    public JComponent createComponent(boolean darkTheme) {
        JEditorPane editor = createHtmlPane(darkTheme);
        
        // Update content - sanitize HTML entities for Swing's HTML renderer
        var sanitized = IncrementalBlockRenderer.sanitizeForSwing(html);
        editor.setText("<html><body>" + sanitized + "</body></html>");
        
        // Configure for left alignment and proper sizing
        editor.setAlignmentX(Component.LEFT_ALIGNMENT);
        editor.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        
        return editor;
    }
    
    @Override
    public void updateComponent(JComponent component) {
        if (component instanceof JEditorPane editor) {
            // Record current scroll position
            var viewport = SwingUtilities.getAncestorOfClass(JViewport.class, editor);
            Point viewPosition = viewport instanceof JViewport jViewport ? jViewport.getViewPosition() : null;
            
            // Update content - sanitize HTML entities for Swing's HTML renderer
            var sanitized = IncrementalBlockRenderer.sanitizeForSwing(html);
            editor.setText("<html><body>" + sanitized + "</body></html>");
            
            // Restore scroll position if possible
            if (viewport instanceof JViewport jViewport && viewPosition != null) {
                jViewport.setViewPosition(viewPosition);
            }
        }
    }

    /**
     * Creates a JEditorPane for HTML content with base CSS to match the theme.
     */
    private JEditorPane createHtmlPane(boolean isDarkTheme) {
        var htmlPane = new JEditorPane();
        htmlPane.setContentType("text/html");
        htmlPane.setEditable(false);
        // Don't set NEVER_UPDATE as it can interfere with mouse events
        // Keep default caret behavior for better mouse event handling
        htmlPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // Add a simple mouse listener to test if events are received
        htmlPane.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                System.out.println("DEBUG: Basic mouse click on MarkdownComponentData at " + e.getPoint());
            }
        });
        htmlPane.setText("<html><body></body></html>");

        var bgColor = ThemeColors.getColor(isDarkTheme, "message_background");

        htmlPane.setBackground(bgColor);

        var kit = (HTMLEditorKit) htmlPane.getEditorKit();
        var ss = kit.getStyleSheet();

        // Base background and text color
        var bgColorHex = ColorUtil.toHex(bgColor);
        var textColor = ThemeColors.getColor(isDarkTheme, "chat_text");
        var textColorHex = ColorUtil.toHex(textColor);
        var linkColor = ThemeColors.getColorHex(isDarkTheme, "link_color_hex");

        // Define theme-specific colors
        var borderColor = ThemeColors.getColorHex(isDarkTheme, "border_color_hex");
        // Base typography
        ss.addRule("body { font-family: 'Segoe UI', system-ui, sans-serif; line-height: 1.5; " +
                           "background-color: " + bgColorHex + "; color: " + textColorHex + "; margin: 0; padding-left: 8px; padding-right: 8px; }");

        // Headings
        ss.addRule("h1, h2, h3, h4, h5, h6 { margin-top: 18px; margin-bottom: 12px; " +
                           "font-weight: 600; line-height: 1.25; color: " + textColorHex + "; }");
        ss.addRule("h1 { font-size: 1.5em; border-bottom: 1px solid " + borderColor + "; padding-bottom: 0.2em; }");
        ss.addRule("h2 { font-size: 1.3em; border-bottom: 1px solid " + borderColor + "; padding-bottom: 0.2em; }");
        ss.addRule("h3 { font-size: 1.1em; }");
        ss.addRule("h4 { font-size: 1em; }");

        // Links
        ss.addRule("a { color: " + linkColor + "; text-decoration: none; }");
        ss.addRule("a:hover { text-decoration: underline; }");

        // Paragraphs and lists
        ss.addRule("p, ul, ol { margin-top: 0; margin-bottom: 12px; }");
        ss.addRule("ul, ol { padding-left: 2em; }");
        ss.addRule("li { margin: 0.25em 0; }");
        ss.addRule("li > p { margin-top: 12px; }");

        // Code styling
        ss.addRule("code { font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace; " +
                           "padding: 0.2em 0.4em; margin: 0; font-size: 85%; border-radius: 3px; " +
                           "color: " + linkColor + "; }");

        // Table styling
        ss.addRule("table { border-collapse: collapse; margin: 15px 0; width: 100%; }");
        ss.addRule("table, th, td { border: 1px solid " + borderColor + "; }");
        ss.addRule("th { background-color: " + ThemeColors.getColorHex(isDarkTheme, "code_block_background") + "; " +
                           "padding: 8px; text-align: left; font-weight: 600; }");
        ss.addRule("td { padding: 8px; }");
        ss.addRule("tr:nth-child(even) { background-color: " + ThemeColors.getColorHex(isDarkTheme, "message_background") + "; }");
        ss.addRule("tr:hover { background-color: " + ThemeColors.getColorHex(isDarkTheme, "chat_background") + "; }");
        
        // Search highlighting classes - using same colors as diff tool (Colors.SEARCH and Colors.CURRENT_SEARCH)
        var searchColorHex = ColorUtil.toHex(Colors.SEARCH);
        var currentSearchColorHex = ColorUtil.toHex(Colors.CURRENT_SEARCH);
        
        ss.addRule("." + SearchConstants.SEARCH_HIGHLIGHT_CLASS + " { background-color: " + searchColorHex + "; color: black; }");
        ss.addRule("." + SearchConstants.SEARCH_CURRENT_CLASS + " { background-color: " + currentSearchColorHex + "; color: black; }");
        
        // Badge styling
        ss.addRule(".badge { display: inline-block; padding: 0.15em 0.4em; margin-left: 0.25em; " +
                   "font-size: 75%; font-weight: 700; line-height: 1; text-align: center; " +
                   "white-space: nowrap; vertical-align: baseline; border-radius: 0.25rem; }");
        ss.addRule(".badge-symbol { background-color: #17a2b8; color: white; }");
        ss.addRule(".badge-file { background-color: #28a745; color: white; }");
        ss.addRule(".badge-class { background-color: #6610f2; color: white; }");
        ss.addRule(".badge-function { background-color: #fd7e14; color: white; }");
        ss.addRule(".badge-field { background-color: #20c997; color: white; }");
        ss.addRule(".badge-module { background-color: #6f42c1; color: white; }");
        
        // Clickable badge styling
        ss.addRule(".clickable-badge { cursor: pointer; }");
        ss.addRule(".clickable-badge:hover { opacity: 0.8; text-decoration: none; }");

        return htmlPane;
    }
}
