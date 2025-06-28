package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.nodes.Element;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Composite implementation of HtmlCustomizer that applies multiple customizers in sequence.
 * This allows combining different HTML customization behaviors, such as symbol badges
 * and search highlighting, without conflicts.
 * Automatically deduplicates customizers based on their IDs to prevent redundant processing.
 */
public class CompositeHtmlCustomizer implements HtmlCustomizer {
    private static final Logger logger = LogManager.getLogger(CompositeHtmlCustomizer.class);
    
    private final List<HtmlCustomizer> customizers;
    private static final int CUSTOMIZER_ID = 1000; // Reserved ID for composite customizer

    /**
     * Creates a composite customizer with the given customizers.
     * Customizers are applied in the order provided, with duplicates automatically removed.
     *
     * @param customizers the customizers to apply in sequence
     */
    public CompositeHtmlCustomizer(HtmlCustomizer... customizers) {
        this.customizers = deduplicateCustomizers(Arrays.asList(customizers));
    }

    /**
     * Creates a composite customizer with the given list of customizers.
     * Customizers are applied in the order provided, with duplicates automatically removed.
     *
     * @param customizers the customizers to apply in sequence
     */
    public CompositeHtmlCustomizer(List<HtmlCustomizer> customizers) {
        this.customizers = deduplicateCustomizers(customizers);
    }

    @Override
    public void customize(Element root) {
        logger.debug("CompositeHtmlCustomizer applying {} customizers to DOM with {} characters", 
                    customizers.size(), root.html().length());
        
        for (int i = 0; i < customizers.size(); i++) {
            HtmlCustomizer customizer = customizers.get(i);
            logger.debug("Applying customizer {}/{}: {}", i + 1, customizers.size(), customizer.getClass().getSimpleName());
            customizer.customize(root);
            logger.debug("Customizer {}/{} complete. DOM now has {} characters", 
                        i + 1, customizers.size(), root.html().length());
        }
        
        logger.debug("CompositeHtmlCustomizer complete. Final DOM: {}", 
                    root.html().length() > 200 ? root.html().substring(0, 200) + "..." : root.html());
    }
    
    @Override
    public int getCustomizerId() {
        return CUSTOMIZER_ID;
    }
    
    /**
     * Removes duplicate customizers based on their IDs while preserving order.
     * The first occurrence of each customizer ID is kept.
     */
    private static List<HtmlCustomizer> deduplicateCustomizers(List<HtmlCustomizer> input) {
        if (input.isEmpty()) {
            return List.of();
        }
        
        Set<Integer> seenIds = new LinkedHashSet<>();
        return input.stream()
                .filter(Objects::nonNull)
                .filter(customizer -> seenIds.add(customizer.getCustomizerId()))
                .collect(Collectors.toList());
    }

    /**
     * Creates a new composite that includes an additional customizer.
     *
     * @param additional the customizer to add
     * @return a new composite with the additional customizer
     */
    public CompositeHtmlCustomizer with(HtmlCustomizer additional) {
        var newCustomizers = new java.util.ArrayList<>(customizers);
        newCustomizers.add(additional);
        return new CompositeHtmlCustomizer(newCustomizers);
    }

    /**
     * Gets the number of customizers in this composite.
     *
     * @return the number of customizers
     */
    public int size() {
        return customizers.size();
    }

    /**
     * Checks if this composite is empty (has no customizers).
     *
     * @return true if empty, false otherwise
     */
    public boolean isEmpty() {
        return customizers.isEmpty();
    }
}