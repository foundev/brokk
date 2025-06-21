package io.github.jbellis.brokk.gui.mop.stream;

import javax.swing.*;
import java.awt.event.MouseEvent;

/**
 * Interface for handling clicks on badges in the rendered HTML content.
 * Implementations can respond to clicks on file badges, symbol badges, etc.
 */
@FunctionalInterface
public interface BadgeClickHandler {
    /**
     * Called when a badge is clicked.
     * 
     * @param badgeType The type of badge clicked (e.g., "file", "symbol")
     * @param badgeData The data associated with the badge (e.g., file path, symbol name)
     * @param event The mouse event that triggered the click
     * @param component The component where the click occurred
     */
    void onBadgeClick(String badgeType, String badgeData, MouseEvent event, JComponent component);
}