package sandbox;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebView;

import javax.swing.*;
import java.awt.*;

/** Stand-alone PoC: Swing frame + JavaFX WebView, no Brokk deps. */
public final class WebViewSpike {
    private static final String MARKDOWN_SAMPLE = """
        # Hello Brokk
        This is **bold** text.
        ```java
        System.out.println("Hello Java21");
        ```
        - bullet 1
        - bullet 2
        
        """.repeat(500);

    public static void main(String[] args) {
        // 1. Start JavaFX runtime *before* touching Swing UI.
        Platform.startup(() -> {});

        SwingUtilities.invokeLater(() -> {
            var f = new JFrame("WebView spike");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.setSize(800, 600);

            // 2. Host container
            var panel = new JFXPanel();      // creates FX thread plumbing
            f.add(panel, BorderLayout.CENTER);

            // 3. Create WebView on FX thread
            Platform.runLater(() -> {
                var view = new WebView();

                // disable context menu, clipboard etc. We only test speed.
                view.setContextMenuEnabled(false);

                panel.setScene(new Scene(view));

                // 4. Hard-coded HTML that converts Markdown via markdown-it
                view.getEngine().loadContent(bootstrapHtml(MARKDOWN_SAMPLE));
            });

            f.setVisible(true);
        });
    }

    private static String bootstrapHtml(String md) {
        /* Inline markdown-it for a self-contained spike.
           Real implementation will bundle via WebPack/Vite. */
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset='utf-8'>
              <script src='https://cdn.jsdelivr.net/npm/markdown-it@14/dist/markdown-it.min.js'></script>
              <style>
                body{font-family: system-ui; margin:0; padding:16px;}
                pre{background:#f3f3f3; border-radius:4px; padding:8px;}
              </style>
            </head>
            <body id="root"></body>
            <script>
              const mdIt = window.markdownit({linkify:true});
              const root = document.getElementById('root');
              const md = %s;
              let pos = 0;
              setInterval(() => {
                if (pos >= md.length) {
                  return;
                }
                pos += 40; // bytes
                root.innerHTML = mdIt.render(md.slice(0, pos));
              }, 20);
            </script>
            </html>
            """.formatted(escapeForJs(md));
    }

    private static String escapeForJs(String text) {
        // Escape for use inside a JavaScript single-quoted string literal
        var escaped = text.replace("\\", "\\\\")
                          .replace("'", "\\'")
                          .replace("\n", "\\n")
                          .replace("\r", "");
        return "'" + escaped + "'";
    }
}
