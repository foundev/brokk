package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.stream.HtmlCustomizer;
import io.github.jbellis.brokk.gui.mop.stream.TextNodeMarkerCustomizer;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SearchCallback implementation for searching within multiple MarkdownOutputPanels.
 */
public class MarkdownPanelSearchCallback implements SearchCallback {
    private static final Logger logger = LogManager.getLogger(MarkdownPanelSearchCallback.class);
    
    private final List<MarkdownOutputPanel> panels;
    private String currentSearchTerm = "";
    private List<Integer> allMarkerIds = new ArrayList<>();
    private int currentMarkerIndex = -1;
    
    public MarkdownPanelSearchCallback(List<MarkdownOutputPanel> panels) {
        this.panels = panels;
    }
    
    @Override
    public SearchResults performSearch(SearchCommand command) {
        String searchTerm = command.searchText();
        boolean caseSensitive = command.isCaseSensitive();
        
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            stopSearch();
            return SearchResults.noMatches();
        }
        
        searchTerm = searchTerm.trim();
        this.currentSearchTerm = searchTerm;
        
        // Create search customizer
        HtmlCustomizer searchCustomizer = new TextNodeMarkerCustomizer(
            searchTerm,
            caseSensitive,
            false,  // don't require whole word matching for better search experience
            "<strong style='background-color:yellow; color:black;'>",
            "</strong>"
        );
        
        // Apply search highlighting to all panels and collect marker IDs
        allMarkerIds.clear();
        logger.debug("Applying search customizer for term: '{}'", searchTerm);
        
        // Track how many panels need to be processed
        var panelCount = panels.size();
        var processedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        
        // Apply customizer to all panels with callback
        for (MarkdownOutputPanel panel : panels) {
            panel.setHtmlCustomizerWithCallback(searchCustomizer, () -> {
                // This runs after each panel's customizer is applied
                if (processedCount.incrementAndGet() == panelCount) {
                    // All panels processed, now collect marker IDs
                    logger.debug("All panels processed, collecting marker IDs");
                    for (MarkdownOutputPanel p : panels) {
                        p.renderers().forEach(renderer -> {
                            var markerIds = renderer.getIndexedMarkerIds();
                            logger.debug("Renderer has {} marker IDs: {}", markerIds.size(), markerIds);
                            allMarkerIds.addAll(markerIds);
                        });
                    }
                    
                    logger.debug("Total marker IDs found: {}", allMarkerIds.size());
                    
                    // Reset current position
                    currentMarkerIndex = allMarkerIds.isEmpty() ? -1 : 0;
                    
                    if (!allMarkerIds.isEmpty()) {
                        // Scroll to the first match
                        scrollToCurrentMarker();
                    }
                }
            });
        }
        
        // For now, return a placeholder result. The actual count will be updated when marker IDs are collected.
        // This is a limitation of the async nature, but the UI will still work.
        return SearchResults.withMatches(1, 1);
    }
    
    @Override
    public void goToPreviousResult() {
        if (allMarkerIds.isEmpty()) {
            return;
        }
        
        // Move to previous match (wrap around to end if at beginning)
        currentMarkerIndex = (currentMarkerIndex - 1 + allMarkerIds.size()) % allMarkerIds.size();
        scrollToCurrentMarker();
    }
    
    @Override
    public void goToNextResult() {
        if (allMarkerIds.isEmpty()) {
            return;
        }
        
        // Move to next match (wrap around to beginning if at end)
        currentMarkerIndex = (currentMarkerIndex + 1) % allMarkerIds.size();
        scrollToCurrentMarker();
    }
    
    private void scrollToCurrentMarker() {
        if (currentMarkerIndex < 0 || currentMarkerIndex >= allMarkerIds.size()) {
            return;
        }
        
        int markerId = allMarkerIds.get(currentMarkerIndex);
        logger.debug("Scrolling to marker ID {} (index {} of {})", markerId, currentMarkerIndex + 1, allMarkerIds.size());
        
        // Find which renderer contains this marker and scroll to it
        for (MarkdownOutputPanel panel : panels) {
            // Check all renderers in this panel using the direct API
            Optional<JComponent> foundComponent = panel.renderers()
                .map(renderer -> renderer.findByMarkerId(markerId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
                
            if (foundComponent.isPresent()) {
                logger.debug("Found component for marker ID {}: {}", markerId, foundComponent.get().getClass().getSimpleName());
                // Scroll the component into view
                SwingUtilities.invokeLater(() -> {
                    JComponent comp = foundComponent.get();
                    
                    // Find the scroll pane and scroll to the component
                    Container parent = comp.getParent();
                    while (parent != null) {
                        if (parent instanceof JScrollPane scrollPane) {
                            Rectangle bounds = comp.getBounds();
                            if (comp.getParent() != scrollPane.getViewport().getView()) {
                                // Convert bounds to viewport coordinates if needed
                                bounds = SwingUtilities.convertRectangle(comp.getParent(), bounds, scrollPane.getViewport().getView());
                            }
                            // Adjust to show some context around the match
                            bounds.y = Math.max(0, bounds.y - 50);
                            bounds.height += 100;
                            scrollPane.getViewport().scrollRectToVisible(bounds);
                            logger.debug("Scrolled to bounds: {}", bounds);
                            break;
                        }
                        parent = parent.getParent();
                    }
                });
                return; // Found and scrolled to the marker
            }
        }
        
        logger.debug("Marker ID {} not found in any renderer", markerId);
    }
    
    @Override
    public void stopSearch() {
        logger.debug("Stopping search");
        // Clear search highlighting from all panels
        for (MarkdownOutputPanel panel : panels) {
            panel.setHtmlCustomizer(HtmlCustomizer.DEFAULT);
        }
        currentSearchTerm = "";
        allMarkerIds.clear();
        currentMarkerIndex = -1;
    }
    
    public String getCurrentSearchTerm() {
        return currentSearchTerm;
    }
    
    public SearchResults getCurrentResults() {
        if (allMarkerIds.isEmpty()) {
            return SearchResults.noMatches();
        }
        return SearchResults.withMatches(allMarkerIds.size(), currentMarkerIndex + 1);
    }
}