package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

/**
 * Demonstrates badge click functionality.
 * To run as a visual test, uncomment the @Test annotation.
 */
public class BadgeClickTest {
    
    // @Test  // Uncomment to run visual test
    public void testBadgeClicks() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Badge Click Test");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(800, 600);
            
            // Create a markdown panel
            MarkdownOutputPanel panel = new MarkdownOutputPanel();
            panel.updateTheme(false); // Light theme
            
            // Set up badge click handler
            BadgeClickHandler handler = (badgeType, badgeData, event, component) -> {
                System.out.println("=== Badge Click Detected ===");
                System.out.println("Type: " + badgeType);
                System.out.println("Data: " + badgeData);
                System.out.println("Position: " + event.getPoint());
                System.out.println("Component: " + component.getClass().getSimpleName());
                System.out.println("==========================");
            };
            
            // Apply handler to all renderers
            panel.renderers().forEach(renderer -> {
                renderer.setBadgeClickHandler(handler);
            });
            
            // Add test content with files
            String content = """
                # Test Badge Clicks
                
                Here are some files to test:
                - `src/main/java/Example.java`
                - `config/application.properties`
                - `README.md`
                
                Click on the green [F] badges that appear next to recognized files!
                """;
            
            panel.append(content, dev.langchain4j.data.message.ChatMessageType.AI, true);
            
            // Add to frame
            JScrollPane scrollPane = new JScrollPane(panel);
            frame.add(scrollPane);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
        
        // Keep test running for manual interaction
        Thread.sleep(30000);
    }
}