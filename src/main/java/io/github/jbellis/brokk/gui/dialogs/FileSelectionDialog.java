package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.gui.FileSelectionPanel;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * A file selection dialog that presents a tree view and a text input with autocomplete
 * for selecting a SINGLE file. Accepts pre-filled autocomplete candidates.
 * Uses FileSelectionPanel for its core UI.
 */
public class FileSelectionDialog extends JDialog {

    private final FileSelectionPanel fileSelectionPanel;
    private final JButton okButton;
    private final JButton cancelButton;

    private @Nullable BrokkFile selectedFile;
    private boolean confirmed;

    /**
     * Constructor for single file selection, potentially with external candidates.
     *
     * @param parent                 Parent frame.
     * @param project                The current project (can be null if allowExternalFiles is true).
     * @param title                  Dialog title.
     * @param allowExternalFiles     If true, shows the full file system.
     * @param fileFilter             Optional predicate to filter files in the tree (external mode only).
     * @param autocompleteCandidates Optional collection of external file paths for autocompletion.
     */
    public FileSelectionDialog(Frame parent, @Nullable IProject project, String title, boolean allowExternalFiles,
                              @Nullable Predicate<@Nullable File> fileFilter, @Nullable Future<List<Path>> autocompleteCandidates) {
        super(parent, title, true); // modal dialog

        // Configure the FileSelectionPanel
        var panelConfig = new FileSelectionPanel.Config(project,
                                                       allowExternalFiles,
                                                       fileFilter,
                                                       autocompleteCandidates,
                                                       false, // multiSelect = false 
                                                       this::handlePanelSingleFileConfirmed,
                                                       true, // includeProjectFilesInAutocomplete
                                                       ""); // help text
        fileSelectionPanel = new FileSelectionPanel(panelConfig);

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        mainPanel.add(fileSelectionPanel, BorderLayout.CENTER);

        // Buttons at the bottom
        okButton = new JButton("OK");
        okButton.addActionListener(e -> doOk());
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            selectedFile = null;
            dispose();
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Handle escape key
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getRootPane().registerKeyboardAction(e -> {
            confirmed = false;
            selectedFile = null;
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as default
        getRootPane().setDefaultButton(okButton);

        // Focus input on open (delegate to panel's input component)
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                SwingUtilities.invokeLater(() -> { // Ensure panel components are ready
                    fileSelectionPanel.getFileInputComponent().requestFocusInWindow();
                });
            }
        });

        setContentPane(mainPanel);
        pack(); // Pack after adding all components
        // Preferred size can be set on the panel itself or here after packing
        // setSize(new Dimension(Math.max(550, getWidth()), Math.max(500, getHeight())));
        setLocationRelativeTo(parent);
    }

    private void handlePanelSingleFileConfirmed(@Nullable BrokkFile file) {
        this.selectedFile = file;
        this.confirmed = file != null;
        if (file != null) {
            fileSelectionPanel.setInputText(file.absPath().toString());
        }
        dispose();
    }

    private void doOk() {
        List<BrokkFile> resolvedFiles = fileSelectionPanel.resolveAndGetSelectedFiles();
        this.selectedFile = resolvedFiles.isEmpty() ? null : resolvedFiles.getFirst();
        this.confirmed = this.selectedFile != null;
        
        String currentInput = fileSelectionPanel.getInputText().trim();
        if (!resolvedFiles.isEmpty() || currentInput.isEmpty()) {
            dispose();
        } else {
            JOptionPane.showMessageDialog(this,
                                        "Could not resolve file: " + currentInput,
                                        "File Not Found", 
                                        JOptionPane.ERROR_MESSAGE);
        }
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public @Nullable BrokkFile getSelectedFile() {
        return selectedFile;
    }
}
