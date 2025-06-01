package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.stream.HtmlCustomizer;
import io.github.jbellis.brokk.gui.mop.stream.TextNodeMarkerCustomizer;

import java.awt.*;
import java.util.List;

/**
 * SearchCallback implementation for searching within multiple MarkdownOutputPanels.
 */
public class MarkdownPanelSearchCallback implements SearchCallback {
    private final List<MarkdownOutputPanel> panels;
    private String currentSearchTerm = "";
    
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
            true,  // whole word matching
            "<strong style='background-color:yellow; color:black;'>",
            "</strong>"
        );
        
        // Apply search highlighting to all panels
        int totalMatches = 0;
        for (MarkdownOutputPanel panel : panels) {
            panel.setHtmlCustomizer(searchCustomizer);
            // Note: We don't have a way to count matches precisely here
            // The customizer will highlight all matches automatically
        }
        
        // For now, return a simple result indicating search was performed
        // In a more sophisticated implementation, we could count actual matches
        if (!searchTerm.isEmpty()) {
            return SearchResults.withMatches(1, 1); // Placeholder values
        }
        
        return SearchResults.noMatches();
    }
    
    @Override
    public void goToPreviousResult() {
        // For now, no navigation between individual results
        // The highlighting shows all matches at once
    }
    
    @Override
    public void goToNextResult() {
        // For now, no navigation between individual results
        // The highlighting shows all matches at once
    }
    
    @Override
    public void stopSearch() {
        // Clear search highlighting from all panels
        for (MarkdownOutputPanel panel : panels) {
            panel.setHtmlCustomizer(HtmlCustomizer.DEFAULT);
        }
        currentSearchTerm = "";
    }
    
    public String getCurrentSearchTerm() {
        return currentSearchTerm;
    }
}