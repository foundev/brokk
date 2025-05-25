package io.github.jbellis.brokk.difftool.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaHighlighter;

import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * A Highlighter that extends {@link RSyntaxTextAreaHighlighter} to provide
 * base syntax highlighting and then delegates to a secondary {@link Highlighter}
 * (typically {@link JMHighlighter}) for additional decorations like diff/search.
 *
 * Mutating operations (add/remove highlights) are forwarded to the
 * secondary highlighter.
 */
public class CompositeHighlighter extends RSyntaxTextAreaHighlighter
{
    private final Highlighter secondary;

    /**
     * Creates a composite highlighter.
     *
     * @param secondaryHighlighter The secondary highlighter to use for additional decorations.
     *                             This is typically a {@link JMHighlighter}. It must not be null.
     */
    public CompositeHighlighter(Highlighter secondaryHighlighter)
    {
        super(); // Initializes this as an RSyntaxTextAreaHighlighter
        if (secondaryHighlighter == null) {
            throw new IllegalArgumentException("Secondary highlighter cannot be null");
        }
        this.secondary = secondaryHighlighter;
    }

    /* -------------------------------------------------- install / de-install */

    @Override
    public void install(JTextComponent c)
    {
        super.install(c); // RSyntaxTextAreaHighlighter's install
        // The 'secondary' highlighter should also be associated with the component
        // if it tracks the component state independently (like JMHighlighter does).
        secondary.install(c);
    }

    @Override
    public void deinstall(JTextComponent c)
    {
        super.deinstall(c); // RSyntaxTextAreaHighlighter's deinstall
        secondary.deinstall(c);
    }

    /* -------------------------------------------------- painting */

    @Override
    public void paint(Graphics g)
    {
        super.paint(g);     // RSyntaxTextAreaHighlighter paints syntax highlights
        secondary.paint(g);   // Secondary paints diff/search highlights on top
    }

    /* -------------------------------------------------- highlight mutation
       – forward to JMHighlighter (secondary) */
    @Override
    public Object addHighlight(int p0, int p1, HighlightPainter painter) throws BadLocationException
    {
        // Delegate to secondary for custom highlights (diffs, search results)
        return secondary.addHighlight(p0, p1, painter);
    }

    @Override
    public void removeHighlight(Object tag)
    {
        secondary.removeHighlight(tag);
    }

    @Override
    public void removeAllHighlights()
    {
        secondary.removeAllHighlights();
    }

    @Override
    public void changeHighlight(Object tag, int p0, int p1) throws BadLocationException
    {
        secondary.changeHighlight(tag, p0, p1);
    }

    @Override
    public Highlight[] getHighlights()
    {
        // Highlights from RSyntaxTextAreaHighlighter (e.g., syntax, mark occurrences)
        Highlight[] a = super.getHighlights();
        // Highlights from JMHighlighter (diffs, search results)
        Highlight[] b = secondary.getHighlights();

        return Stream.concat(Arrays.stream(a), Arrays.stream(b))
                     .toArray(Highlight[]::new);
    }
}
