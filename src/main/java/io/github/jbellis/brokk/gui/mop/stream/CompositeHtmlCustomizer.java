package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.nodes.Element;

import java.util.Arrays;
import java.util.List;

/**
 * Composite implementation of HtmlCustomizer that applies multiple customizers in sequence.
 * This allows combining different HTML customization behaviors, such as symbol badges
 * and search highlighting, without conflicts.
 */
public class CompositeHtmlCustomizer implements HtmlCustomizer {
    private final List<HtmlCustomizer> customizers;

    /**
     * Creates a composite customizer with the given customizers.
     * Customizers are applied in the order provided.
     *
     * @param customizers the customizers to apply in sequence
     */
    public CompositeHtmlCustomizer(HtmlCustomizer... customizers) {
        this.customizers = Arrays.asList(customizers);
    }

    /**
     * Creates a composite customizer with the given list of customizers.
     * Customizers are applied in the order provided.
     *
     * @param customizers the customizers to apply in sequence
     */
    public CompositeHtmlCustomizer(List<HtmlCustomizer> customizers) {
        this.customizers = List.copyOf(customizers);
    }

    @Override
    public void customize(Element root) {
        for (HtmlCustomizer customizer : customizers) {
            if (customizer != null) {
                System.out.println("call " + customizer.getClass().getSimpleName() + ".customize()" + " on " + root);
                try {
                    customizer.customize(root);
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        }
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