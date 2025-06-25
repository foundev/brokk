package io.github.jbellis.brokk.gui.mop.stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Dedicated class for detecting clicks on badge elements in HTML content.
 * Handles the complex logic of mapping click positions to file references.
 */
public final class BadgeClickDetector {
    private static final Logger logger = LogManager.getLogger(BadgeClickDetector.class);
    
    // Pre-compiled regex patterns for performance
    private static final Pattern SIMPLE_FILE_PATTERN = Pattern.compile(
        "<[^>]*title=\"file:([^:]+):id:(\\d+)\"[^>]*>.*?>([^<]+)<", 
        Pattern.DOTALL);
    
    private static final Pattern CLICKABLE_PATTERN = Pattern.compile(
        "<[^>]*class=\"[^\"]*clickable-file-badge[^\"]*\"[^>]*title=\"file:([^:]+):id:(\\d+)\"[^>]*>.*?>([^<]+)<", 
        Pattern.DOTALL);
    
    private static final Pattern TITLE_PATTERN = Pattern.compile(
        "title=\"file:([^:]+):id:(\\d+)\"[^>]*>.*?>([^<]+)<", 
        Pattern.DOTALL);
    
    private static final Pattern SIMPLEST_PATTERN = Pattern.compile(
        "title=\"file:([^\"]+)\"[^>]*>.*?>([^<]+)<", 
        Pattern.DOTALL);

    /**
     * Result of click detection containing the filename if a badge was clicked.
     */
    public static class ClickResult {
        private final boolean found;
        private final String fileName;
        
        private ClickResult(boolean found, String fileName) {
            this.found = found;
            this.fileName = fileName;
        }
        
        public static ClickResult found(String fileName) {
            return new ClickResult(true, fileName);
        }
        
        public static ClickResult notFound() {
            return new ClickResult(false, "");
        }
        
        public boolean isFound() { return found; }
        public String getFileName() { return fileName; }
    }
    
    /**
     * Helper class to store filename occurrence information.
     */
    private static class FileOccurrence {
        final int start;
        final int end;
        final String fileName;
        
        FileOccurrence(int start, int end, String fileName) {
            this.start = start;
            this.end = end;
            this.fileName = fileName;
        }
    }
    
    /**
     * Detects if a click at the given position hits a file badge.
     * 
     * @param editor The JEditorPane containing the content
     * @param clickPosition The position where the click occurred
     * @param enableDetection Whether badge click detection is enabled
     * @return ClickResult indicating if a badge was clicked and which file
     */
    public static ClickResult detectBadgeClick(JEditorPane editor, int clickPosition, boolean enableDetection) {
        if (clickPosition < 0 || !enableDetection) {
            return ClickResult.notFound();
        }
        
        logger.info("=== BADGE CLICK DETECTION START ===");
        logger.info("Click detected at position: {}", clickPosition);
        
        try {
            String htmlContent = editor.getText();
            String textContent = editor.getDocument().getText(0, editor.getDocument().getLength());
            
            logger.info("HTML content length: {}, Text content length: {}", htmlContent.length(), textContent.length());
            
            // First try regex-based detection
            var regexResult = detectViaRegexPatterns(htmlContent, textContent, clickPosition);
            if (regexResult.isFound()) {
                logger.info("*** CLICK MATCH FOUND via regex *** filename: {}", regexResult.getFileName());
                return regexResult;
            }
            
            // Fallback to element-based detection
            var elementResult = detectViaElementAttributes(editor, clickPosition);
            if (elementResult.isFound()) {
                logger.info("*** CLICK MATCH FOUND via element *** filename: {}", elementResult.getFileName());
                return elementResult;
            }
            
            logger.info("No badge click detected");
            return ClickResult.notFound();
            
        } catch (Exception ex) {
            logger.error("Error processing click at position {}: {}", clickPosition, ex.getMessage(), ex);
            return ClickResult.notFound();
        } finally {
            logger.info("=== BADGE CLICK DETECTION END ===");
        }
    }
    
    /**
     * Attempts to detect badge clicks using regex pattern matching.
     */
    private static ClickResult detectViaRegexPatterns(String htmlContent, String textContent, int clickPosition) {
        var occurrences = new ArrayList<FileOccurrence>();
        
        // Log sample HTML for debugging
        logSampleHtml(htmlContent);
        
        // Try patterns in order of specificity
        if (tryPattern(SIMPLE_FILE_PATTERN, htmlContent, textContent, occurrences, "SIMPLE") ||
            tryPattern(CLICKABLE_PATTERN, htmlContent, textContent, occurrences, "CLICKABLE") ||
            tryPattern(TITLE_PATTERN, htmlContent, textContent, occurrences, "TITLE") ||
            tryPattern(SIMPLEST_PATTERN, htmlContent, textContent, occurrences, "SIMPLEST")) {
            
            // Check if click position matches any occurrence
            return checkOccurrences(occurrences, clickPosition);
        }
        
        return ClickResult.notFound();
    }
    
    /**
     * Tries a specific regex pattern to find file occurrences.
     */
    private static boolean tryPattern(Pattern pattern, String htmlContent, String textContent, 
                                    List<FileOccurrence> occurrences, String patternName) {
        var matcher = pattern.matcher(htmlContent);
        int count = 0;
        
        while (matcher.find()) {
            count++;
            String fileName = extractFileName(matcher, patternName);
            String filenameContent = extractFilenameContent(matcher, patternName);
            
            logger.info("*** FOUND CLICKABLE FILE #{} ({}): file='{}', content='{}' ***", 
                       count, patternName, fileName, filenameContent);
            
            addOccurrences(textContent, filenameContent, fileName, occurrences);
        }
        
        logger.debug("Pattern {} found {} matches", patternName, count);
        return count > 0;
    }
    
    /**
     * Extracts filename from regex matcher based on pattern type.
     */
    private static String extractFileName(java.util.regex.Matcher matcher, String patternName) {
        if ("SIMPLEST".equals(patternName)) {
            String titleContent = matcher.group(1);
            return titleContent.contains(":") ? 
                titleContent.substring(0, titleContent.indexOf(":")) : titleContent;
        }
        return matcher.group(1);
    }
    
    /**
     * Extracts filename content from regex matcher based on pattern type.
     */
    private static String extractFilenameContent(java.util.regex.Matcher matcher, String patternName) {
        return "SIMPLEST".equals(patternName) ? matcher.group(2) : matcher.group(3);
    }
    
    /**
     * Adds all occurrences of filename content to the occurrences list.
     */
    private static void addOccurrences(String textContent, String filenameContent, 
                                     String fileName, List<FileOccurrence> occurrences) {
        int searchStart = 0;
        int filenameStart;
        while ((filenameStart = textContent.indexOf(filenameContent, searchStart)) >= 0) {
            int filenameEnd = filenameStart + filenameContent.length();
            occurrences.add(new FileOccurrence(filenameStart, filenameEnd, fileName));
            logger.debug("Added occurrence: {} at {}-{}", fileName, filenameStart, filenameEnd);
            searchStart = filenameEnd;
        }
    }
    
    /**
     * Checks if click position matches any file occurrence.
     */
    private static ClickResult checkOccurrences(List<FileOccurrence> occurrences, int clickPosition) {
        logger.info("Checking {} occurrences for click at position {}", occurrences.size(), clickPosition);
        
        for (FileOccurrence occurrence : occurrences) {
            logger.info("Checking occurrence: {} at {}-{}", occurrence.fileName, occurrence.start, occurrence.end);
            if (clickPosition >= occurrence.start && clickPosition <= occurrence.end) {
                return ClickResult.found(occurrence.fileName);
            }
        }
        
        return ClickResult.notFound();
    }
    
    /**
     * Attempts to detect badge clicks using element attribute inspection.
     */
    private static ClickResult detectViaElementAttributes(JEditorPane editor, int clickPosition) {
        logger.debug("Trying element-based detection");
        
        HTMLDocument doc = (HTMLDocument) editor.getDocument();
        javax.swing.text.Element elem = doc.getCharacterElement(clickPosition);
        AttributeSet attrs = elem.getAttributes();
        
        // Check element hierarchy for title attribute
        while (elem != null && attrs != null) {
            Object titleAttr = attrs.getAttribute(HTML.Attribute.TITLE);
            if (titleAttr != null) {
                String title = titleAttr.toString();
                logger.debug("Found title attribute: {}", title);
                
                if (title.startsWith("file:") && title.contains(":id:")) {
                    int colonIndex = title.indexOf(':', 5);
                    if (colonIndex > 5) {
                        String fileName = title.substring(5, colonIndex);
                        logger.debug("Extracted filename from title: {}", fileName);
                        return ClickResult.found(fileName);
                    }
                }
            }
            
            // Try parent element
            elem = elem.getParentElement();
            if (elem != null) {
                attrs = elem.getAttributes();
            }
        }
        
        return ClickResult.notFound();
    }
    
    /**
     * Logs a sample of HTML content for debugging purposes.
     */
    private static void logSampleHtml(String htmlContent) {
        int clickableIndex = htmlContent.indexOf("badge-file");
        if (clickableIndex >= 0) {
            int start = Math.max(0, clickableIndex - 100);
            int end = Math.min(htmlContent.length(), clickableIndex + 200);
            logger.info("Sample HTML around clickable file: {}", htmlContent.substring(start, end));
        } else if (htmlContent.contains("file:")) {
            logger.info("Found 'file:' in HTML, checking structure...");
            int fileIndex = htmlContent.indexOf("file:");
            int start = Math.max(0, fileIndex - 100);
            int end = Math.min(htmlContent.length(), fileIndex + 200);
            logger.info("HTML around 'file:': {}", htmlContent.substring(start, end));
        } else {
            logger.info("No 'badge-file' found in HTML. Sample of HTML content: {}", 
                       htmlContent.length() > 500 ? htmlContent.substring(0, 500) + "..." : htmlContent);
        }
    }
}