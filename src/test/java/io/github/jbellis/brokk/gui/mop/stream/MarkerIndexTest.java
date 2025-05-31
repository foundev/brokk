package io.github.jbellis.brokk.gui.mop.stream;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that IncrementalBlockRenderer can locate Swing components by marker id.
 */
public class MarkerIndexTest {

    @Test
    public void testFindByMarkerId() throws Exception {
        var renderer = new IncrementalBlockRenderer(false);
        renderer.setHtmlCustomizer(new TextNodeMarkerCustomizer("alpha", true, true,
                                                                "<mark>", "</mark>"));

        // Run update on EDT to ensure components are created
        SwingUtilities.invokeAndWait(() -> renderer.update("one alpha two"));

        var ids = renderer.getIndexedMarkerIds();
        assertEquals(1, ids.size(), "Exactly one marker id expected");
        int id = ids.iterator().next();

        AtomicReference<JComponent> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                ref.set(renderer.findByMarkerId(id).orElse(null)));

        assertNotNull(ref.get(), "Renderer should resolve component for marker id");
    }
}
