package io.github.jbellis.brokk.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Java AWT
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

// Java Util
import java.util.List;
import java.util.Objects;

// Java Swing
import javax.swing.*;
import javax.swing.text.JTextComponent;

/**
 * Utility class for managing autocomplete functionality in the Brokk GUI.
 * Handles popup sizing, key bindings, and completion descriptions.
 */
public class AutoCompleteUtil {
    private static final Logger logger = LogManager.getLogger(AutoCompleteUtil.class);

    // Popup sizing constants
    private static final int MAX_POPUP_WIDTH = 800;
    private static final int MAX_POPUP_HEIGHT = 400;
    private static final int MAX_VISIBLE_ROWS = 15; // Limit rows shown without scrolling
    
    // Padding and spacing constants
    private static final int HORIZONTAL_PADDING = 40;
    private static final int VERTICAL_PADDING = 20;
    private static final int ROW_PADDING = 2;
    
    // Font adjustment factor
    private static final double DESC_WIDTH_FACTOR = 1.2; // Adjustment for monospaced font in description

    /**
     * Binds Ctrl+Enter to accept the current autocomplete suggestion if the popup is visible;
     * if the autocomplete popup is not open, it submits the dialog by triggering its default button.
     *
     * @param autoCompletion The AutoCompletion instance managing the autocomplete popup.
     * @param textComponent The text component that the autocomplete is attached to.
     */
    public static void bindCtrlEnter(@NotNull AutoCompletion autoCompletion, @NotNull JTextComponent textComponent) {
        Objects.requireNonNull(autoCompletion, "autoCompletion");
        Objects.requireNonNull(textComponent, "textComponent");
        var ctrlEnter = Objects.requireNonNull(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
            "Failed to create KeyStroke for Ctrl+Enter");

        textComponent.getInputMap(JComponent.WHEN_FOCUSED)
                .put(ctrlEnter, "ctrlEnterAction");

        textComponent.getActionMap()
                .put("ctrlEnterAction", new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        if (autoCompletion.isPopupVisible()) {
                            // If the autocomplete popup is open, simulate the Enter key press
                            // by invoking the action ACPW temporarily bound to it.
                            var enterAction = textComponent.getActionMap().get("Enter");
                            if (enterAction != null) {
                                enterAction.actionPerformed(new ActionEvent(
                                        textComponent, // Source
                                        ActionEvent.ACTION_PERFORMED, // ID
                                        null, // Command string (can be null)
                                        e.getWhen(), // Use original event timestamp
                                        e.getModifiers() // Use original event modifiers
                                ));
                            } else {
                                logger.error("Could not find Enter action in text component's action map");
                            }
                        }
                        else {
                            // Otherwise, "click" the default button on the current root pane
                            var rootPane = SwingUtilities.getRootPane(textComponent);
                            if (rootPane == null) {
                                logger.debug("No root pane found for text component");
                                return;
                            }
                            var defaultButton = rootPane.getDefaultButton();
                            if (defaultButton == null) {
                                logger.debug("No default button found on root pane");
                                return;
                            }
                            defaultButton.doClick();
                        }
                    }
                });
    }

    /**
     * Dynamically adjusts the size of the AutoCompletion popup windows (choices and description)
     * based on the content of the completions.
     *
     * @param autoCompletion The AutoCompletion instance whose popups need resizing.
     * @param textComponent The text component the AutoCompletion is attached to (used for font metrics).
     * @param completions   The list of completions to display.
     */
    public static void sizePopupWindows(@NotNull AutoCompletion autoCompletion, @NotNull JTextComponent textComponent, @NotNull List<ShorthandCompletion> completions) {
        Objects.requireNonNull(autoCompletion, "autoCompletion");
        Objects.requireNonNull(textComponent, "textComponent");
        Objects.requireNonNull(completions, "completions");

        var defaultFontMetrics = textComponent.getFontMetrics(textComponent.getFont());
        int rowHeight = defaultFontMetrics.getHeight() + ROW_PADDING;
        int visibleRowCount = Math.min(completions.size(), MAX_VISIBLE_ROWS);
        int calculatedHeight = visibleRowCount * rowHeight + VERTICAL_PADDING;
        int popupHeight = Math.min(MAX_POPUP_HEIGHT, calculatedHeight);

        // Calculate Choices window width
        int maxInputWidth = completions.stream()
                .mapToInt(c -> defaultFontMetrics.stringWidth(c.getInputText() + " - " + c.getShortDescription()))
                .max().orElse(200); // Default width if stream is empty (shouldn't happen here)
        int choicesWidth = Math.min(MAX_POPUP_WIDTH, maxInputWidth + HORIZONTAL_PADDING);
        autoCompletion.setChoicesWindowSize(choicesWidth, popupHeight);

        // Calculate Description window width and show it
        var ttFontMetrics = textComponent.getFontMetrics(UIManager.getFont("ToolTip.font"));
        boolean hasDescriptions = completions.stream()
                .anyMatch(c -> {
                    var desc = getCompletionDescription(c);
                    return desc != null && !desc.isEmpty();
                });
        // disabled for https://github.com/bobbylight/AutoComplete/issues/97
        if (hasDescriptions) {
            int maxDescWidth = completions.stream()
                    .map(AutoCompleteUtil::getCompletionDescription)
                    .filter(Objects::nonNull)
                    .mapToInt(ttFontMetrics::stringWidth)
                    .max().orElse(300); // Default width
            // Apply hack factor for potentially monospaced font in description
            int descWidth = Math.min(MAX_POPUP_WIDTH, (int) (DESC_WIDTH_FACTOR * maxDescWidth) + HORIZONTAL_PADDING);

            autoCompletion.setShowDescWindow(true);
            autoCompletion.setDescriptionWindowSize(descWidth, popupHeight);
        } else {
            autoCompletion.setShowDescWindow(false);
        }
    }

    /**
     * Helper to get the description text, handling ShorthandCompletion.
     */
    private static @Nullable String getCompletionDescription(@NotNull Completion c) {
        return c == null ? null
             : c instanceof ShorthandCompletion sc ? sc.getReplacementText()
             : c.getToolTipText();
    }
}
