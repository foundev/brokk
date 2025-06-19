package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Chrome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;

/**
 * A JButton extension that shows a loading state with spinner animation.
 * <p>
 * Features:
 * <ul>
 *   <li>Visual loading indicator during async operations</li>
 *   <li>Automatic state restoration when loading completes</li>
 *   <li>Theme-aware spinner icons</li>
 *   <li>Graceful fallback when resources missing</li>
 * </ul>
 */
public final class LoadingButton extends JButton {
    private static final Logger logger = LogManager.getLogger(LoadingButton.class);

    private static Icon spinnerDark;
    private static Icon spinnerLight;

    private final Chrome chrome;
    private String idleText;
    @Nullable
    private String idleTooltip;
    @Nullable
    private Icon idleIcon;

    public LoadingButton(String initialText,
                         @Nullable Icon initialIcon,
                         Chrome chrome,
                         @Nullable ActionListener actionListener)
    {
        super(initialText, initialIcon);
        this.idleText = initialText;
        this.idleIcon = initialIcon;
        // Capture tooltip that might have been set by super() or default
        this.idleTooltip = getToolTipText();
        this.chrome = chrome;

        if (actionListener != null) {
            addActionListener(actionListener);
        }
    }

    /**
     * Sets the loading state of the button. 
     * <p>
     * When loading is true:
     * <ul>
     *   <li>Shows a spinner animation</li>
     *   <li>Displays busy text (if provided)</li>
     *   <li>Shows wait cursor</li>
     *   <li>Disables button interaction</li>
     * </ul>
     * When loading is false, restores all properties to their idle state.
     * <p>
     * This method must be called on the Event Dispatch Thread (EDT).
     *
     * @param loading   true to enter loading state, false to return to idle state
     * @param busyText  the text to display when loading (null shows default processing message)
     * @throws IllegalStateException if not called on EDT
     */
    public void setLoading(boolean loading, @Nullable String busyText) {
        assert SwingUtilities.isEventDispatchThread() : "LoadingButton.setLoading must be called on the EDT";

        if (loading) {
            // idleText, idleIcon, idleTooltip are already up-to-date via overridden setters
            // or from construction if no setters were called while enabled.

            var spinnerIcon = getCachedSpinnerIcon();
            super.setIcon(spinnerIcon);
            super.setDisabledIcon(spinnerIcon); // Show spinner even when disabled
            super.setText(busyText);
            super.setToolTipText(busyText != null ? busyText : "Processing...");
            super.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            super.setEnabled(false); // Disable the button
        } else {
            super.setIcon(idleIcon);
            super.setDisabledIcon(null); // No special disabled icon for idle state unless idleIcon handles it
            super.setText(idleText);
            super.setToolTipText(idleTooltip);
            super.setCursor(Cursor.getDefaultCursor());
            super.setEnabled(true); // Re-enable the button
        }
    }

    @Override
    public void setText(String text) {
        // No explicit null check needed if `text` is not @Nullable, let NullAway enforce.
        // If it can be null, it should be @Nullable in method signature.
        super.setText(text);
        if (isEnabled()) { // Only update idleText if not in loading state (isEnabled is false during loading)
            this.idleText = text;
        }
    }

    @Override
    public void setIcon(Icon icon) {
        super.setIcon(icon);
        if (isEnabled()) {
            this.idleIcon = icon;
        }
    }

    @Override
    public void setToolTipText(@Nullable String text) {
        super.setToolTipText(text);
        if (isEnabled()) {
            this.idleTooltip = text;
        }
    }

    @Nullable
    private Icon getCachedSpinnerIcon() {
        assert SwingUtilities.isEventDispatchThread() : "getCachedSpinnerIcon must be called on the EDT";
        boolean isDark = chrome.getTheme().isDarkTheme();
        Icon cachedIcon = isDark ? spinnerDark : spinnerLight;

        if (cachedIcon == null) {
            String path = "/icons/" + (isDark ? "spinner_dark.gif" : "spinner_white.gif");
            var url = getClass().getResource(path);

            if (url == null) {
                logger.warn("Spinner icon resource not found: {}", path);
                // Create simple placeholder icon (using default text from the previous version)
                var placeholder = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                var g = placeholder.createGraphics();
                try {
                    g.setColor(isDark ? Color.WHITE : Color.BLACK);
                    // Placeholder text can be simple or based on a constant if needed elsewhere
                    g.drawString("...", 0, 12); // Using simple values to avoid new fields for placeholder
                } finally {
                    g.dispose();
                }
                cachedIcon = new ImageIcon(placeholder);
            } else {
                ImageIcon originalIcon = new ImageIcon(url);
                // Create a new ImageIcon from the Image to ensure animation restarts
                cachedIcon = new ImageIcon(originalIcon.getImage());
            }

            // Given the assert SwingUtilities.isEventDispatchThread(), double-checked locking is unnecessary.
            // Simple assignment is sufficient and safer on the EDT.
            if (isDark) {
                spinnerDark = cachedIcon;
            } else {
                spinnerLight = cachedIcon;
            }
        }
        // Return the now-cached icon, which will be non-null if it was created/found.
        // It's guaranteed not to be null here because it's either retrieved from cache or created.
        return isDark ? spinnerDark : spinnerLight;
    }
}
