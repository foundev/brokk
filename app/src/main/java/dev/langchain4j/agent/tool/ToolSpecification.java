package dev.langchain4j.agent.tool;

import static dev.langchain4j.internal.Utils.quoted;

import java.util.Objects;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Describes a tool that language model can execute.
 * <p>
 * Can be generated automatically from methods annotated with {@link Tool} using {@link ToolSpecifications} helper.
 */
public class ToolSpecification {

    private final String name;
    private final String description;
    private final JsonObjectSchema parameters;

    /**
     * Creates a {@link ToolSpecification} from a {@link Builder}.
     *
     * @param builder the builder.
     */
    private ToolSpecification(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.parameters = builder.parameters;
    }

    /**
     * Returns the name of the tool.
     *
     * @return the name of the tool.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the description of the tool.
     *
     * @return the description of the tool.
     */
    public String description() {
        return description;
    }

    /**
     * Returns the parameters of the tool.
     */
    public JsonObjectSchema parameters() {
        return parameters;
    }

    @Override
    public boolean equals(Object another) {
        if (this == another) return true;
        return another instanceof ToolSpecification ts
                && equalTo(ts);
    }

    private boolean equalTo(ToolSpecification another) {
        return Objects.equals(name, another.name)
                && Objects.equals(description, another.description)
                && Objects.equals(parameters, another.parameters);
    }

    @Override
    public int hashCode() {
        int h = 5381;
        h += (h << 5) + Objects.hashCode(name);
        h += (h << 5) + Objects.hashCode(description);
        h += (h << 5) + Objects.hashCode(parameters);
        return h;
    }

    @Override
    public String toString() {
        return "ToolSpecification {"
                + " name = " + quoted(name)
                + ", description = " + quoted(description)
                + ", parameters = " + parameters
                + " }";
    }

    /**
     * Creates builder to build {@link ToolSpecification}.
     *
     * @return created builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@code ToolSpecification} builder static inner class.
     */
    public static final class Builder {

        private String name;
        private String description;
        private JsonObjectSchema parameters;

        /**
         * Creates a {@link Builder}.
         */
        private Builder() {
        }

        /**
         * Sets the {@code name}.
         *
         * @param name the {@code name}
         * @return {@code this}
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the {@code description}.
         *
         * @param description the {@code description}
         * @return {@code this}
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the {@code parameters}.
         *
         * @param parameters the {@code parameters}
         * @return {@code this}
         */
        public Builder parameters(JsonObjectSchema parameters) {
            this.parameters = parameters;
            return this;
        }

        /**
         * Returns a {@code ToolSpecification} built from the parameters previously set.
         *
         * @return a {@code ToolSpecification} built with parameters of this {@code ToolSpecification.Builder}
         */
        public ToolSpecification build() {
            return new ToolSpecification(this);
        }
    }
}
