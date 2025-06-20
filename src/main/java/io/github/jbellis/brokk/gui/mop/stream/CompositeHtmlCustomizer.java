package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.nodes.Element;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Composite implementation of HtmlCustomizer that applies multiple customizers in sequence.
 * This allows combining different HTML customization behaviors, such as symbol badges
 * and search highlighting, without conflicts.
 * 
 * Automatically deduplicates customizers based on their IDs to prevent redundant processing.
 */
public class CompositeHtmlCustomizer implements HtmlCustomizer {
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
        for (HtmlCustomizer customizer : customizers) {
            if (customizer != null) {
                customizer.customize(root);
            }
        }
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
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        
        Set<Integer> seenIds = new LinkedHashSet<>();
        return input.stream()
                .filter(customizer -> customizer != null && seenIds.add(customizer.getCustomizerId()))
                .collect(Collectors.toList());
    }

    /**
     * Creates a new composite that includes an additional customizer.
     *
     * @param additional the customizer to add
     * @return a new composite with the additional customizer
     */
    public CompositeHtmlCustomizer with(HtmlCustomizer additional) {
        if (additional == null) {
            return this;
        }
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