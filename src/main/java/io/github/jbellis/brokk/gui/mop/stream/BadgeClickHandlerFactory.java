package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.TableUtils;
import io.github.jbellis.brokk.gui.util.ContextMenuUtils;
import io.github.jbellis.brokk.EditBlock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.event.MouseEvent;

/**
 * Factory for creating badge click handlers with common implementations.
 * Reduces code duplication across different UI components that need badge click functionality.
 */
public final class BadgeClickHandlerFactory {
    private static final Logger logger = LogManager.getLogger(BadgeClickHandlerFactory.class);
    
    private BadgeClickHandlerFactory() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Creates a standard file badge click handler that shows a context menu.
     * 
     * @param contextManager The context manager for resolving files
     * @param chrome The Chrome instance for UI operations
     * @return A BadgeClickHandler that handles file badge clicks
     */
    public static BadgeClickHandler createFileClickHandler(IContextManager contextManager, Chrome chrome) {
        return createFileClickHandler(contextManager, chrome, () -> {});
    }
    
    /**
     * Creates a file badge click handler with a custom refresh callback.
     * 
     * @param contextManager The context manager for resolving files
     * @param chrome The Chrome instance for UI operations
     * @param onRefresh Callback to execute when the menu requests a refresh
     * @return A BadgeClickHandler that handles file badge clicks
     */
    public static BadgeClickHandler createFileClickHandler(IContextManager contextManager, 
                                                          Chrome chrome, 
                                                          Runnable onRefresh) {
        return (badgeType, badgeData, event, component) -> {
            if ("file".equals(badgeType)) {
                handleFileClick(badgeData, event, component, contextManager, chrome, onRefresh);
            }
            // Future: Add handlers for other badge types (symbol, class, etc.)
        };
    }
    
    /**
     * Common implementation for handling file badge clicks with optional refresh callback.
     */
    private static void handleFileClick(String fileName, 
                                       MouseEvent event, 
                                       JComponent component,
                                       IContextManager contextManager, 
                                       Chrome chrome,
                                       Runnable onRefresh) {
        try {
            // Try to resolve the file to get ProjectFile if it exists
            var projectFile = EditBlock.resolveProjectFile(contextManager, fileName);
            var fileRefData = new TableUtils.FileReferenceList.FileReferenceData(
                    fileName,
                    projectFile.absPath().toString(),
                    projectFile
            );
            
            showFileContextMenu(component, event, fileRefData, chrome, onRefresh);
            
        } catch (Exception e) {
            logger.debug("Failed to resolve file for badge: {}", fileName, e);
            
            // If file resolution fails, create a minimal FileReferenceData
            var fileRefData = new TableUtils.FileReferenceList.FileReferenceData(
                    fileName,
                    fileName, // Use filename as path when resolution fails
                    null // No ProjectFile available
            );
            
            showFileContextMenu(component, event, fileRefData, chrome, onRefresh);
        }
    }
    
    /**
     * Shows the file context menu at the appropriate location.
     */
    private static void showFileContextMenu(JComponent component,
                                          MouseEvent event,
                                          TableUtils.FileReferenceList.FileReferenceData fileRefData,
                                          Chrome chrome,
                                          Runnable onRefresh) {
        // Show the same popup menu as WorkspacePanel file badges
        ContextMenuUtils.showFileRefMenu(
                component,
                event.getX(),
                event.getY(),
                fileRefData,
                chrome,
                onRefresh
        );
    }
}