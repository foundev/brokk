package io.github.jbellis.brokk.gui.search;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.Objects;

/**
 * A reusable search bar panel that can be used with any component implementing SearchCallback.
 */
public class SearchBarPanel extends JPanel {
    private static final String CP_FOREGROUND = "SearchBar.foreground";
    
    private JTextField searchField;
    private JLabel searchResult;
    private Timer timer;
    private final SearchCallback searchCallback;
    private final boolean showCaseSensitive;
    private final boolean showNavigation;
    private JCheckBox caseSensitiveCheckBox;
    
    /**
     * Creates a search bar panel with the given callback.
     * 
     * @param searchCallback The callback to handle search operations
     */
    public SearchBarPanel(SearchCallback searchCallback) {
        this(searchCallback, true, true);
    }
    
    /**
     * Creates a search bar panel with the given callback and case sensitivity option.
     * 
     * @param searchCallback The callback to handle search operations
     * @param showCaseSensitive Whether to show the case sensitive checkbox
     */
    public SearchBarPanel(SearchCallback searchCallback, boolean showCaseSensitive) {
        this(searchCallback, showCaseSensitive, true);
    }
    
    /**
     * Creates a search bar panel with the given callback and UI options.
     * 
     * @param searchCallback The callback to handle search operations
     * @param showCaseSensitive Whether to show the case sensitive checkbox
     * @param showNavigation Whether to show navigation buttons and result counter
     */
    public SearchBarPanel(SearchCallback searchCallback, boolean showCaseSensitive, boolean showNavigation) {
        this.searchCallback = searchCallback;
        this.showCaseSensitive = showCaseSensitive;
        this.showNavigation = showNavigation;
        init();
    }
    
    private void init() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        
        // Search field row
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        searchField = new JTextField(20);
        
        // Timer for delayed search
        timer = new Timer(300, e -> performSearch());
        timer.setRepeats(false);
        
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                timer.restart();
            }
            
            @Override
            public void removeUpdate(DocumentEvent e) {
                timer.restart();
            }
            
            @Override
            public void changedUpdate(DocumentEvent e) {
                timer.restart();
            }
        });
        
        searchField.addActionListener(e -> performSearch());
        
        searchPanel.add(new JLabel("Find:"));
        searchPanel.add(searchField);
        
        // Case sensitive checkbox (optional)
        if (showCaseSensitive) {
            caseSensitiveCheckBox = new JCheckBox("Case sensitive");
            caseSensitiveCheckBox.addActionListener(e -> performSearch());
            searchPanel.add(caseSensitiveCheckBox);
        }
        
        // Add components to main panel
        add(Box.createVerticalStrut(5));
        add(searchPanel);
        
        if (showNavigation) {
            // Buttons row
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
            
            JButton previousButton = new JButton("Previous");
            if (hasIcon("/images/prev.png")) {
                previousButton.setIcon(new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/prev.png"))));
            }
            previousButton.addActionListener(getPreviousAction());
            initButton(previousButton);
            
            JButton nextButton = new JButton("Next");
            if (hasIcon("/images/next.png")) {
                nextButton.setIcon(new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/next.png"))));
            }
            nextButton.addActionListener(getNextAction());
            initButton(nextButton);
            
            JButton clearButton = new JButton("Clear");
            clearButton.addActionListener(getClearAction());
            initButton(clearButton);
            
            buttonPanel.add(previousButton);
            buttonPanel.add(Box.createHorizontalStrut(5));
            buttonPanel.add(nextButton);
            buttonPanel.add(Box.createHorizontalStrut(5));
            buttonPanel.add(clearButton);
            
            add(Box.createVerticalStrut(5));
            add(buttonPanel);
        }
        
        // Search result label
        searchResult = new JLabel();
        
        if (showNavigation) {
            add(Box.createVerticalStrut(5));
            add(searchResult);
        }
    }
    
    private boolean hasIcon(String path) {
        return getClass().getResource(path) != null;
    }
    
    private void initButton(AbstractButton button) {
        button.setFocusable(false);
        button.setBorderPainted(false);
        button.setBorder(new EmptyBorder(0, 5, 0, 5));
    }
    
    public SearchCommand getCommand() {
        boolean caseSensitive = caseSensitiveCheckBox != null && caseSensitiveCheckBox.isSelected();
        return new SearchCommand(searchField.getText(), caseSensitive);
    }
    
    public String getSearchText() {
        return searchField.getText();
    }
    
    public void setSearchText(String text) {
        searchField.setText(text);
    }
    
    public void focusSearchField() {
        searchField.requestFocusInWindow();
    }
    
    public void findNext() {
        searchCallback.goToNextResult();
        updateNavigationResults();
    }
    
    public void findPrevious() {
        searchCallback.goToPreviousResult();
        updateNavigationResults();
    }
    
    public void clearSearch() {
        searchField.setText("");
        searchCallback.stopSearch();
        searchResult.setIcon(null);
        searchResult.setText("");
    }
    
    
    public void performSearch() {
        SearchResults results = searchCallback.performSearch(getCommand());
        updateSearchResults(results);
    }
    
    public void updateSearchResults(SearchResults results) {
        boolean notFound = results == null || results.isEmpty();
        String searchText = searchField.getText();
        
        if (notFound && !searchText.isEmpty()) {
            // Set error state
            if (searchField.getForeground() != Color.red) {
                searchField.putClientProperty(CP_FOREGROUND, searchField.getForeground());
                searchField.setForeground(Color.red);
            }
            
            if (hasIcon("/images/result.png")) {
                searchResult.setIcon(new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/result.png"))));
            }
            searchResult.setText("Phrase not found");
        } else {
            // Reset to normal state
            Color originalColor = (Color) searchField.getClientProperty(CP_FOREGROUND);
            if (originalColor != null) {
                searchField.setForeground(originalColor);
                searchField.putClientProperty(CP_FOREGROUND, null);
            }
            
            if (results != null && results.hasMatches()) {
                searchResult.setIcon(null);
                searchResult.setText(String.format("%d of %d", results.getCurrentMatch(), results.getTotalMatches()));
            } else if (!searchText.isEmpty()) {
                searchResult.setIcon(null);
                searchResult.setText("");
            } else {
                searchResult.setIcon(null);
                searchResult.setText("");
            }
        }
    }
    
    private ActionListener getClearAction() {
        return ae -> clearSearch();
    }
    
    private ActionListener getPreviousAction() {
        return ae -> findPrevious();
    }
    
    private ActionListener getNextAction() {
        return ae -> findNext();
    }
    
    private void updateNavigationResults() {
        // For callbacks that support getCurrentResults, update the display
        if (searchCallback instanceof MarkdownPanelSearchCallback markdownCallback) {
            SearchResults results = markdownCallback.getCurrentResults();
            updateSearchResults(results);
        }
    }
    
    /**
     * Registers Ctrl/Cmd+F shortcut to focus the search field.
     */
    public void registerSearchFocusShortcut(JComponent targetComponent) {
        KeyStroke focusSearchKey = KeyStroke.getKeyStroke(KeyEvent.VK_F, 
            java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        
        targetComponent.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(focusSearchKey, "focusSearch");
        targetComponent.getActionMap().put("focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                focusSearchField();
            }
        });
    }
    
}