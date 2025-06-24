package io.github.jbellis.brokk.gui;

import javax.swing.*;
import java.awt.*;

/**
 * Simple panel used by GitLogTab’s merge dialog.
 * Presents the merge–strategy selector and a status label
 * that callers can update with conflict-check results.
 */
public class MergeBranchDialogPanel extends JPanel {
    private final JComboBox<GitWorktreeTab.MergeMode> mergeModeComboBox;
    private final JLabel conflictStatusLabel;

    public MergeBranchDialogPanel(String sourceBranch, String targetBranch) {
        super(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.weightx = 1.0;

        var title = new JLabel(String.format("Merge branch '%s' into '%s'",
                                             sourceBranch,
                                             targetBranch));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(title, gbc);

        // --- merge mode selector -------------------------------------------------
        gbc.gridwidth = 1;
        gbc.weightx   = 0;
        add(new JLabel("Merge strategy:"), gbc);

        gbc.gridx    = 1;
        gbc.weightx  = 1.0;
        mergeModeComboBox = new JComboBox<>(GitWorktreeTab.MergeMode.values());
        mergeModeComboBox.setSelectedItem(GitWorktreeTab.MergeMode.MERGE_COMMIT);
        add(mergeModeComboBox, gbc);

        // --- conflict status label ----------------------------------------------
        gbc.gridx       = 0;
        gbc.gridy       = 2;
        gbc.gridwidth   = GridBagConstraints.REMAINDER;
        gbc.weightx     = 1.0;
        conflictStatusLabel = new JLabel(" ");
        conflictStatusLabel.setForeground(UIManager.getColor("Label.foreground"));
        add(conflictStatusLabel, gbc);
    }

    public JComboBox<GitWorktreeTab.MergeMode> getMergeModeComboBox() {
        return mergeModeComboBox;
    }

    public JLabel getConflictStatusLabel() {
        return conflictStatusLabel;
    }
}
