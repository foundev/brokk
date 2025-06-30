package io.github.jbellis.brokk.gui.mop;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.mop.webview.MOPWebViewHost;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

/**
 * A Swing JPanel that uses a JavaFX WebView to display structured conversations.
 * This is a modern, web-based alternative to the pure-Swing MarkdownOutputPanel.
 */
public class MarkdownOutputPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPanel.class);

    private final MOPWebViewHost webHost;
    private boolean blockClearAndReset = false;
    private final List<Runnable> textChangeListeners = new ArrayList<>();

    public MarkdownOutputPanel(boolean escapeHtml) {
        super(new BorderLayout());
        logger.info("Initializing WebView-based MarkdownOutputPanel");
        this.webHost = new MOPWebViewHost();
        add(webHost, BorderLayout.CENTER);
    }

    public MarkdownOutputPanel() {
        this(true);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        updateTheme(guiTheme.isDarkTheme());
    }

    public void updateTheme(boolean isDark) {
        webHost.setTheme(isDark);
    }

    public void setBlocking(boolean blocked) {
        this.blockClearAndReset = blocked;
    }

    public boolean isBlocking() {
        return blockClearAndReset;
    }

    public void clear() {
        if (blockClearAndReset) {
            logger.debug("Ignoring clear() request while blocking is enabled");
            return;
        }
        webHost.clear();
        textChangeListeners.forEach(Runnable::run);
    }

    public void append(String text, ChatMessageType type, boolean isNewMessage) {
        if (text.isEmpty()) {
            return;
        }
        webHost.append(text, isNewMessage, type);
        textChangeListeners.forEach(Runnable::run);
    }

    public void setText(ContextFragment.TaskFragment newOutput) {
        if (blockClearAndReset) {
            logger.debug("Ignoring setText() request while blocking is enabled");
            return;
        }
        setText(newOutput.messages());
    }

    public void setText(List<ChatMessage> newMessages) {
        if (blockClearAndReset) {
            logger.debug("Ignoring setText() request while blocking is enabled");
            return;
        }
        webHost.clear();
        for (var message : newMessages) {
            webHost.append(Messages.getText(message), true, message.type());
        }
        // All appends are sent, now flush to make sure they are processed.
        webHost.flushAsync();
        textChangeListeners.forEach(Runnable::run);
    }

    public void setText(TaskEntry taskEntry) {
        if (blockClearAndReset) {
            logger.debug("Ignoring setText(TaskEntry) request while blocking is enabled");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (taskEntry.isCompressed()) {
                setText(List.of(Messages.customSystem(Objects.toString(taskEntry.summary(), "Summary not available"))));
            } else {
                var taskFragment = castNonNull(taskEntry.log());
                setText(taskFragment.messages());
            }
        });
    }

    public String getText() {
        logger.warn("getText() is not supported in WebView mode and will return an empty string.");
        return "";
    }

    public List<ChatMessage> getRawMessages() {
        logger.warn("getRawMessages() is not supported in WebView mode and will return an empty list.");
        return List.of();
    }

    public void addTextChangeListener(Runnable listener) {
        textChangeListeners.add(listener);
    }

    public void showSpinner(String message) {
        webHost.showSpinner(message);
    }

    public void hideSpinner() {
        webHost.showSpinner("");
    }

    public List<ChatMessage> getMessages() {
        logger.warn("getMessages() is not supported in WebView mode and will return an empty list.");
        return List.of();
    }

    public CompletableFuture<Void> flushAsync() {
        return webHost.flushAsync();
    }

    public String getDisplayedText() {
        logger.warn("getDisplayedText() is not supported in WebView mode and will return an empty string.");
        return "";
    }

    public String getSelectedText() {
        logger.warn("getSelectedText() is not supported in WebView mode and will return an empty string.");
        return "";
    }

    public void copy() {
        logger.warn("copy() is not yet implemented in WebView mode.");
        // In future, this could be: webHost.copy();
    }

    public void dispose() {
        logger.debug("Disposing WebViewMarkdownOutputPanel.");
        webHost.dispose();
    }

    public CompletableFuture<Void> scheduleCompaction() {
        return CompletableFuture.completedFuture(null);
    }
}
