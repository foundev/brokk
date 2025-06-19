package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.difftool.ui.JMHighlightPainter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages search highlights in text components.
 */
public class HighlightManager {
    private static final Logger logger = LogManager.getLogger(HighlightManager.class);

    private final JTextComponent textComponent;
    private final List<Highlighter.Highlight> searchHighlights = new ArrayList<>();
    @Nullable
    private Highlighter.Highlight currentHighlight = null;

    public HighlightManager(JTextComponent textComponent) {
        this.textComponent = textComponent;
    }

    public void clearHighlights() {
        Highlighter highlighter = textComponent.getHighlighter();
        assert highlighter != null : "Text component must have a highlighter";
        
        for (Highlighter.Highlight highlight : searchHighlights) {
            highlighter.removeHighlight(highlight);
        }
        searchHighlights.clear();
        currentHighlight = null;
    }

    /**
     * Adds a search highlight.
     */
    public Highlighter.Highlight addHighlight(int start, int end, boolean isCurrent) {
        Highlighter highlighter = textComponent.getHighlighter();
        assert highlighter != null : "Text component must have a highlighter";

        try {
            Highlighter.HighlightPainter painter = isCurrent
                ? JMHighlightPainter.CURRENT_SEARCH
                : JMHighlightPainter.SEARCH;
            Object tag = highlighter.addHighlight(start, end, painter);
            Highlighter.Highlight highlight = (Highlighter.Highlight) tag;
            searchHighlights.add(highlight);
            if (isCurrent) {
                currentHighlight = highlight;
            }
            return highlight;
        } catch (BadLocationException e) {
            logger.warn("Failed to add highlight at {}-{}", start, end, e);
            return null; // Original behavior
        }
    }

    /**
     * Updates a highlight from regular to current or vice versa.
     */
    public void updateHighlight(int start, int end, boolean isCurrent) {
        Highlighter highlighter = textComponent.getHighlighter();
        assert highlighter != null : "Text component must have a highlighter";

        // Find and remove the existing highlight
        Highlighter.Highlight[] highlights = highlighter.getHighlights();
        for (Highlighter.Highlight highlight : highlights) {
            if (highlight.getStartOffset() == start && highlight.getEndOffset() == end) {
                Highlighter.HighlightPainter currentPainter = highlight.getPainter();
                if (currentPainter == JMHighlightPainter.SEARCH ||
                    currentPainter == JMHighlightPainter.CURRENT_SEARCH) {
                    highlighter.removeHighlight(highlight);
                    searchHighlights.remove(highlight);
                    break;
                }
            }
        }

        // Add the updated highlight (ignore null returns)
        var ignored = addHighlight(start, end, isCurrent);
    }

    /**
     * Highlights all matches in the text.
     */
    public void highlightAllMatches(List<int[]> matches, int currentMatchIndex) {
        clearHighlights();

        for (int i = 0; i < matches.size(); i++) {
            int[] match = matches.get(i);
            boolean isCurrent = (i == currentMatchIndex);
            var ignored = addHighlight(match[0], match[1], isCurrent);
        }
    }

    public List<Highlighter.Highlight> getSearchHighlights() {
        return new ArrayList<>(searchHighlights);
    }

    @Nullable
    public Highlighter.Highlight getCurrentHighlight() {
        return currentHighlight;
    }
}
