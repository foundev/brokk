package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.stream.HtmlCustomizer;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.gui.mop.stream.TextNodeMarkerCustomizer;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SearchCallback implementation for searching within multiple MarkdownOutputPanels.
 */
public class MarkdownPanelSearchCallback implements SearchCallback {
    private static final Logger logger = LogManager.getLogger(MarkdownPanelSearchCallback.class);
    
    private final List<MarkdownOutputPanel> panels;
    private String currentSearchTerm = "";
    private boolean currentCaseSensitive = false;
    private List<Integer> allMarkerIds = new ArrayList<>();
    private int currentMarkerIndex = -1;
    private SearchBarPanel searchBarPanel;
    private Integer previousHighlightedMarkerId = null;
    
    public MarkdownPanelSearchCallback(List<MarkdownOutputPanel> panels) {
        this.panels = panels;
    }
    
    public void setSearchBarPanel(SearchBarPanel panel) {
        this.searchBarPanel = panel;
    }
    
    @Override
    public SearchResults performSearch(SearchCommand command) {
        String searchTerm = command.searchText();
        boolean caseSensitive = command.isCaseSensitive();
        
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            stopSearch();
            return SearchResults.noMatches();
        }
        
        final String finalSearchTerm = searchTerm.trim();
        
        // If only case sensitivity changed, we need to ensure old highlights are cleared
        boolean onlyCaseChanged = finalSearchTerm.equals(this.currentSearchTerm) && 
                                 caseSensitive != this.currentCaseSensitive;
        
        this.currentSearchTerm = finalSearchTerm;
        this.currentCaseSensitive = caseSensitive;
        this.previousHighlightedMarkerId = null;
        
        // Create search customizer with CSS classes instead of inline styles
        HtmlCustomizer searchCustomizer = new TextNodeMarkerCustomizer(
            finalSearchTerm,
            caseSensitive,
            false,  // don't require whole word matching for better search experience
            "<span class=\"" + SearchConstants.SEARCH_HIGHLIGHT_CLASS + "\">",
            "</span>"
        );
        
        // Apply search highlighting to all panels and collect marker IDs
        allMarkerIds.clear();
        logger.debug("Applying search customizer for term: '{}'", finalSearchTerm);
        
        // Track how many panels need to be processed
        var panelCount = panels.size();
        var remainingOperations = new AtomicInteger(panelCount);
        
        // Apply customizer to all panels
        for (MarkdownOutputPanel panel : panels) {
            Runnable processSearchResults = () -> {
                // This runs after each panel's customizer is applied
                if (remainingOperations.decrementAndGet() == 0) {
                    // All panels processed, now collect marker IDs in visual order
                    logger.debug("All panels processed, collecting marker IDs in visual order");
                    allMarkerIds.clear();
                    collectMarkerIdsInVisualOrder();
                    
                    logger.debug("Total marker IDs found: {}", allMarkerIds.size());
                    
                    // Reset current position
                    currentMarkerIndex = allMarkerIds.isEmpty() ? -1 : 0;
                    
                    if (!allMarkerIds.isEmpty()) {
                        // Highlight the first match as current
                        updateCurrentMatchHighlighting();
                        // Scroll to the first match
                        scrollToCurrentMarker();
                    } else {
                        // No matches found - clear search state and highlighting, then scroll to top
                        logger.debug("No matches found for term '{}', stopping search and clearing highlights", finalSearchTerm);
                        
                        // Clear search state first
                        currentSearchTerm = "";
                        allMarkerIds.clear();
                        currentMarkerIndex = -1;
                        previousHighlightedMarkerId = null;
                        
                        // Clear highlighting from all panels
                        for (MarkdownOutputPanel p : panels) {
                            p.setHtmlCustomizer(HtmlCustomizer.DEFAULT);
                        }
                        
                        // Scroll to top when no matches found
                        scrollToTop();
                    }
                    
                    // Update the search bar panel with the actual results
                    if (searchBarPanel != null) {
                        SwingUtilities.invokeLater(() -> {
                            searchBarPanel.updateSearchResults(getCurrentResults());
                        });
                    }
                }
            };
            
            if (onlyCaseChanged) {
                // For case-only changes, chain the operations to ensure proper sequencing
                logger.debug("Case sensitivity changed - clearing old highlights first");
                panel.setHtmlCustomizerWithCallback(HtmlCustomizer.DEFAULT, () -> {
                    // After clearing is complete, apply the search customizer
                    panel.setHtmlCustomizerWithCallback(searchCustomizer, processSearchResults);
                });
            } else {
                // Normal search - just apply the search customizer
                panel.setHtmlCustomizerWithCallback(searchCustomizer, processSearchResults);
            }
        }
        
        // Return "searching" state since the actual search happens asynchronously
        // The UI should show something like "Searching..." rather than "1 of 1"
        return SearchResults.noMatches();
    }
    
    @Override
    public void goToPreviousResult() {
        if (allMarkerIds.isEmpty()) {
            logger.debug("goToPreviousResult: No search results to navigate");
            return;
        }
        
        int previousIndex = currentMarkerIndex;
        // Move to previous match (wrap around to end if at beginning)
        currentMarkerIndex = (currentMarkerIndex - 1 + allMarkerIds.size()) % allMarkerIds.size();
        
        int currentMarkerId = allMarkerIds.get(currentMarkerIndex);
        logger.info("goToPreviousResult: Moving from index {} to {} (marker ID: {})", 
                    previousIndex, currentMarkerIndex, currentMarkerId);
        
        updateCurrentMatchHighlighting();
        scrollToCurrentMarker();
        
        // Update the search bar panel
        if (searchBarPanel != null) {
            SwingUtilities.invokeLater(() -> {
                searchBarPanel.updateSearchResults(getCurrentResults());
            });
        }
    }
    
    @Override
    public void goToNextResult() {
        if (allMarkerIds.isEmpty()) {
            logger.debug("goToNextResult: No search results to navigate");
            return;
        }
        
        int previousIndex = currentMarkerIndex;
        // Move to next match (wrap around to beginning if at end)
        currentMarkerIndex = (currentMarkerIndex + 1) % allMarkerIds.size();
        
        int currentMarkerId = allMarkerIds.get(currentMarkerIndex);
        logger.info("goToNextResult: Moving from index {} to {} (marker ID: {})", 
                    previousIndex, currentMarkerIndex, currentMarkerId);
        
        updateCurrentMatchHighlighting();
        scrollToCurrentMarker();
        
        // Update the search bar panel
        if (searchBarPanel != null) {
            SwingUtilities.invokeLater(() -> {
                searchBarPanel.updateSearchResults(getCurrentResults());
            });
        }
    }
    
    private void updateCurrentMatchHighlighting() {
        if (allMarkerIds.isEmpty() || currentMarkerIndex < 0 || currentMarkerIndex >= allMarkerIds.size()) {
            return;
        }
        
        int currentMarkerId = allMarkerIds.get(currentMarkerIndex);
        logger.debug("updateCurrentMatchHighlighting: Setting current marker ID to {}", currentMarkerId);
        
        // First, clear previous highlighting if it exists
        if (previousHighlightedMarkerId != null) {
            logger.debug("updateCurrentMatchHighlighting: Clearing previous highlight for marker ID {}", previousHighlightedMarkerId);
            updateMarkerStyleInAllPanels(previousHighlightedMarkerId, false);
        }
        
        // Then highlight the current match
        updateMarkerStyleInAllPanels(currentMarkerId, true);
        previousHighlightedMarkerId = currentMarkerId;
    }
    
    private void updateMarkerStyleInAllPanels(int markerId, boolean isCurrent) {
        SwingUtilities.invokeLater(() -> {
            for (MarkdownOutputPanel panel : panels) {
                panel.renderers().forEach(renderer -> {
                    renderer.updateMarkerStyle(markerId, isCurrent);
                });
            }
        });
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
                            
                            // Position the found marker near the top of the viewport for better context
                            JViewport viewport = scrollPane.getViewport();
                            Rectangle viewRect = viewport.getViewRect();
                            
                            // Calculate desired position: put the marker about 1/4 down from the top
                            int desiredY = Math.max(0, bounds.y - (viewRect.height / 4));
                            
                            // Ensure we don't scroll past the end of the content
                            Component view = viewport.getView();
                            int maxY = Math.max(0, view.getHeight() - viewRect.height);
                            desiredY = Math.min(desiredY, maxY);
                            
                            // Set the viewport position directly for precise control
                            viewport.setViewPosition(new Point(viewRect.x, desiredY));
                            
                            logger.debug("Scrolled to position: y={} (marker bounds: {}, viewport height: {})", 
                                       desiredY, bounds, viewRect.height);
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
        previousHighlightedMarkerId = null;
        
        // Scroll to top after clearing search
        scrollToTop();
    }
    
    /**
     * Scrolls all panels to the top.
     */
    private void scrollToTop() {
        SwingUtilities.invokeLater(() -> {
            for (MarkdownOutputPanel panel : panels) {
                // Find scroll pane containing this panel
                Container parent = panel.getParent();
                while (parent != null) {
                    if (parent instanceof JScrollPane scrollPane) {
                        scrollPane.getViewport().setViewPosition(new Point(0, 0));
                        logger.debug("Scrolled panel to top after clearing search");
                        break;
                    }
                    parent = parent.getParent();
                }
            }
        });
    }
    
    /**
     * Collects marker IDs from all panels and renderers in visual order (top to bottom).
     * This ensures that search navigation follows the natural reading order of the document.
     */
    private void collectMarkerIdsInVisualOrder() {
        // Helper class to track marker position context
        record MarkerContext(int markerId, int panelIndex, int rendererIndex) {}
        
        List<MarkerContext> markerContexts = new ArrayList<>();
        
        // Collect markers with their position context
        for (int panelIndex = 0; panelIndex < panels.size(); panelIndex++) {
            MarkdownOutputPanel panel = panels.get(panelIndex);
            List<IncrementalBlockRenderer> rendererList = panel.renderers().toList();
            
            for (int rendererIndex = 0; rendererIndex < rendererList.size(); rendererIndex++) {
                IncrementalBlockRenderer renderer = rendererList.get(rendererIndex);
                var markerIds = renderer.getIndexedMarkerIds();
                
                logger.debug("Panel {} renderer {} has {} marker IDs: {}", 
                           panelIndex, rendererIndex, markerIds.size(), markerIds);
                
                // Convert marker IDs to contexts for sorting
                for (int markerId : markerIds) {
                    markerContexts.add(new MarkerContext(markerId, panelIndex, rendererIndex));
                }
            }
        }
        
        // Sort by visual position: panel index first, then renderer index, then marker ID
        // The marker ID acts as a tiebreaker for markers within the same renderer,
        // maintaining the natural document order since IDs are generated sequentially
        markerContexts.sort((a, b) -> {
            if (a.panelIndex != b.panelIndex) {
                return Integer.compare(a.panelIndex, b.panelIndex);
            }
            if (a.rendererIndex != b.rendererIndex) {
                return Integer.compare(a.rendererIndex, b.rendererIndex);
            }
            return Integer.compare(a.markerId, b.markerId);
        });
        
        // Extract the sorted marker IDs
        allMarkerIds.clear();
        for (MarkerContext context : markerContexts) {
            allMarkerIds.add(context.markerId);
        }
        
        logger.debug("Collected {} marker IDs in visual order", allMarkerIds.size());
        if (logger.isDebugEnabled() && !allMarkerIds.isEmpty()) {
            logger.debug("First 5 marker IDs: {}", 
                       allMarkerIds.subList(0, Math.min(5, allMarkerIds.size())));
        }
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