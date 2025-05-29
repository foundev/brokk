package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Project;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Manages UI theme settings and application across the application.
 */
public class GuiTheme {
    private static final Logger logger = LogManager.getLogger(GuiTheme.class);
    
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    private final JFrame frame;
    private final JScrollPane mainScrollPane;
    private final Chrome chrome;
    
    // Track registered popup menus that need theme updates
    private final List<JPopupMenu> popupMenus = new ArrayList<>();
    
    /**
     * Creates a new theme manager
     *
     * @param frame The main application frame
     * @param mainScrollPane The main scroll pane for LLM output
     * @param chrome The Chrome instance for UI feedback
     */
    public GuiTheme(JFrame frame, JScrollPane mainScrollPane, Chrome chrome) {
        this.frame = frame;
        this.mainScrollPane = mainScrollPane;
        this.chrome = chrome;
    }
    
    /**
     * Applies the current theme to the application
     * 
     * @param isDark true for dark theme, false for light theme
     */
    public void applyTheme(boolean isDark) {
        String themeName = isDark ? THEME_DARK : THEME_LIGHT;

        try {
            // Save preference first so we know the value is stored
            Project.setTheme(themeName);

            // Apply the theme to the Look and Feel
            if (isDark) {
                com.formdev.flatlaf.FlatDarkLaf.setup();
            } else {
                com.formdev.flatlaf.FlatLightLaf.setup();
            }
            
            // Apply theme to RSyntaxTextArea components
            applyThemeAsync(themeName);
            
            // Update the UI
            SwingUtilities.updateComponentTreeUI(frame);
            
            // Update registered popup menus
            for (JPopupMenu menu : popupMenus) {
                SwingUtilities.updateComponentTreeUI(menu);
            }

            // Make sure scroll panes update properly
            if (mainScrollPane != null) {
                mainScrollPane.revalidate();
            }
        } catch (Exception e) {
            chrome.toolErrorRaw("Failed to switch theme: " + e.getMessage());
        }
    }
    
    /**
     * Applies the appropriate theme to all RSyntaxTextArea components
     * @param themeName "dark" or "light"
     */
    private void applyThemeAsync(String themeName) {
        String themeResource = "/org/fife/ui/rsyntaxtextarea/themes/%s".formatted(themeName.equals(THEME_DARK) ? "dark.xml" : "default.xml");
        Theme theme;
        try {
            theme = Theme.load(getClass().getResourceAsStream(themeResource));
        } catch (Exception e) {
            logger.error("Could not load {} from {}", themeName, themeResource, e);
            return;
        }

        // Apply to all RSyntaxTextArea components in open windows
        SwingUtilities.invokeLater(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JFrame win) {
                    applyThemeToFrame(win, theme);
                }
                if (window instanceof JDialog dialog) {
                    // 1. ThemeAware dialogs can theme themselves
                    if (dialog instanceof ThemeAware aware) {
                        aware.applyTheme(this, theme);
                    }
                    applyThemeRecursively(dialog.getContentPane(), theme);
                }
            }
        });
    }
    
    /**
     * Applies the syntax theme to every relevant component contained in the
     * supplied frame.
     */
    private void applyThemeToFrame(JFrame frame, Theme theme) {
        applyThemeRecursively(frame.getContentPane(), theme);
    }

    /**
     * Recursive depth-first traversal of the Swing component hierarchy that honours the
     * {@link io.github.jbellis.brokk.gui.ThemeAware} contract.
     */
    private void applyThemeRecursively(Component component, Theme theme) {
        if (component == null) {
            return;
        }

        switch (component) {
            // 1. Give ThemeAware components first crack at theming themselves
            case ThemeAware aware -> aware.applyTheme(this, theme);
            // 2. Plain RSyntaxTextArea
            case RSyntaxTextArea area -> theme.apply(area);
            // 3. Handle the common case of RSyntaxTextArea wrapped in a JScrollPane
            case JScrollPane scrollPane -> {
                Component view = scrollPane.getViewport().getView();
                applyThemeRecursively(view, theme);
            }
            default -> { }
        }

        // 4. Recurse into child components (if any)
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyThemeRecursively(child, theme);
            }
        }
    }

    /**
     * Checks if dark theme is currently active
     * @return true if dark theme is active
     */
    public boolean isDarkTheme() {
        return THEME_DARK.equalsIgnoreCase(Project.getTheme());
    }
    
    /**
     * Registers a popup menu to receive theme updates
     * @param menu The popup menu to register
     */
    public void registerPopupMenu(JPopupMenu menu) {
        if (!popupMenus.contains(menu)) {
            popupMenus.add(menu);
            
            // Apply current theme immediately if already initialized
            SwingUtilities.invokeLater(() -> SwingUtilities.updateComponentTreeUI(menu));
        }
    }

    /**
     * Applies the current theme to a specific RSyntaxTextArea
     * @param textArea The text area to apply theme to
     */
    public void applyCurrentThemeToComponent(RSyntaxTextArea textArea) {
        SwingUtilities.invokeLater(() -> {
            try {
                String currentTheme = Project.getTheme();
                String themeResource = "/org/fife/ui/rsyntaxtextarea/themes/" +
                                      (currentTheme.equals(THEME_DARK) ? "dark.xml" : "default.xml");

                Theme.load(getClass().getResourceAsStream(themeResource))
                    .apply(textArea);
            } catch (Exception e) {
                logger.warn("Could not apply theme to RSyntaxTextArea component", e);
            }
        });
    }
}
