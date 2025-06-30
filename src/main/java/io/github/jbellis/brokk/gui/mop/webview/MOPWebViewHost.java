package io.github.jbellis.brokk.gui.mop.webview;

import dev.langchain4j.data.message.ChatMessageType;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class MOPWebViewHost extends JPanel {
    private final JFXPanel fxPanel = new JFXPanel();
    private final AtomicReference<MOPBridge> bridgeRef = new AtomicReference<>();

    public MOPWebViewHost() {
        super(new BorderLayout());
        add(fxPanel, BorderLayout.CENTER);

        Platform.runLater(() -> {
            var view = new WebView();
            var scene = new Scene(view);
            fxPanel.setScene(scene);

            var bridge = new MOPBridge(view.getEngine());
            bridgeRef.set(bridge);

            // Expose Java object to JS after the page loads
            view.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                    var window = (JSObject) view.getEngine().executeScript("window");
                    window.setMember("javaBridge", bridge);
                }
            });

            var resourceUrl = getClass().getResource("/mop-web/index.html");
            if (resourceUrl == null) {
                view.getEngine().loadContent("<html><body><h1>Error: mop-web/index.html not found</h1></body></html>", "text/html");
            } else {
                view.getEngine().load(resourceUrl.toExternalForm());
            }
        });
    }

    public void append(String text, boolean isNewMessage, ChatMessageType msgType) {
        var bridge = bridgeRef.get();
        if (bridge != null) {
            bridge.append(text, isNewMessage, msgType);
        }
    }

    public void setTheme(boolean isDark) {
        var bridge = bridgeRef.get();
        if (bridge != null) {
            bridge.setTheme(isDark);
        }
    }

    public CompletableFuture<Void> flushAsync() {
        var bridge = bridgeRef.get();
        if (bridge != null) {
            return bridge.flushAsync();
        }
        return CompletableFuture.completedFuture(null);
    }

    public void dispose() {
        var bridge = bridgeRef.getAndSet(null);
        if (bridge != null) {
            bridge.shutdown();
        }
        Platform.runLater(() -> {
            if (fxPanel.getScene() != null && fxPanel.getScene().getRoot() instanceof WebView webView) {
                webView.getEngine().load(null); // release memory
            }
        });
    }
}
