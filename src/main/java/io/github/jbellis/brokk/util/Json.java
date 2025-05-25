package io.github.jbellis.brokk.util;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;

/**
 * Single, centrally-configured Jackson {@link ObjectMapper}.
 *
 * *  Registers the JDK 8 and JSR-310 modules automatically
 * *  Turns off timestamp-style dates (makes diffs legible)
 * *  Enables polymorphic “`type`” metadata for every non-final value so the
 *    whole {@code ContextFragment} hierarchy round-trips without sprinkling
 *    {@code @JsonTypeInfo}/{@code @JsonSubTypes} on dozens of classes.
 *
 * Using one mapper keeps boilerplate low—callers just import
 * {@code Json.mapper}.
 */
public final class Json {
    public static final ObjectMapper mapper;

    static {
        mapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .activateDefaultTyping(
                        LaissezFaireSubTypeValidator.instance,
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY   // adds "type": "fqcn" in JSON
                );
    }

    private Json() {}   // no instances
}
