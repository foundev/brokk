package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.stream.HtmlCustomizer;
import io.github.jbellis.brokk.gui.mop.stream.TextNodeMarkerCustomizer;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SearchCallback implementation for searching within multiple MarkdownOutputPanels.
 */
public class MarkdownPanelSearchCallback implements SearchCallback {
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
        for (MarkdownOutputPanel panel : panels) {
            panel.setHtmlCustomizer(searchCustomizer);
            // Get marker IDs from all renderers in this panel using the direct API
            panel.renderers().forEach(renderer -> {
                allMarkerIds.addAll(renderer.getIndexedMarkerIds());
            });
        }
        
        // Reset current position
        currentMarkerIndex = allMarkerIds.isEmpty() ? -1 : 0;
        
        if (!allMarkerIds.isEmpty()) {
            // Scroll to the first match
            scrollToCurrentMarker();
            return SearchResults.withMatches(allMarkerIds.size(), currentMarkerIndex + 1);
        }
        
        return SearchResults.noMatches();
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
        
        // Find which renderer contains this marker and scroll to it
        for (MarkdownOutputPanel panel : panels) {
            // Check all renderers in this panel using the direct API
            Optional<JComponent> foundComponent = panel.renderers()
                .map(renderer -> renderer.findByMarkerId(markerId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
                
            if (foundComponent.isPresent()) {
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
                            break;
                        }
                        parent = parent.getParent();
                    }
                });
                break; // Found and scrolled to the marker
            }
        }
    }
    
    @Override
    public void stopSearch() {
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