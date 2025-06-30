package io.github.jbellis.brokk.gui.mop.webview;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import dev.langchain4j.data.message.ChatMessageType;
import org.jetbrains.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface BrokkEvent {
    String getType();
    @Nullable Integer getEpoch(); // Can return null for events that don't need an ACK

    record Chunk(String text, boolean isNew, @JsonSerialize(using = ToStringSerializer.class) ChatMessageType msgType, int epoch) implements BrokkEvent {
        @Override
        public String getType() {
            return "chunk";
        }

        @Override
        public Integer getEpoch() {
            return epoch;
        }
    }

    record Theme(boolean dark) implements BrokkEvent {
        @Override
        public String getType() {
            return "theme";
        }

        @Override
        public @Nullable Integer getEpoch() {
            return null;
        }
    }

    record Spinner(String message) implements BrokkEvent {
        @Override
        public String getType() {
            return "spinner";
        }

        @Override
        public @Nullable Integer getEpoch() {
            return null;
        }
    }

    record Clear() implements BrokkEvent {
        @Override
        public String getType() {
            return "clear";
        }

        @Override
        public @Nullable Integer getEpoch() {
            return null;
        }
    }
}
