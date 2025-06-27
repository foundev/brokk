package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;

import java.util.List;
import java.util.Set;

/**
 * Represents the outcome of a CodeAgent session, containing all necessary information
 * to update the context history.
 */
public record TaskResult(String actionDescription,
                         ContextFragment.TaskFragment output,
                         Set<ProjectFile> changedFiles,
                         StopDetails stopDetails)
{
    /**
     * The kind of user/agent interaction that produced this result.
     * This determines the icon shown in the history view.
     */
    public enum InteractionMode
    {
        ARCHITECT, CODE, ASK, SEARCH, RUN, UNKNOWN;

        public String toIconName()
        {
            return switch (this)
            {
                case ARCHITECT -> "Brokk.ai-robot";
                case CODE -> "Brokk.ai-robot";
                case ASK -> "Brokk.ask";
                case SEARCH -> "Brokk.search";
                case RUN -> "Brokk.run";
                default -> "Brokk.ai-robot";
            };
        }
    }

    public TaskResult(IContextManager contextManager, String actionDescription,
                      List<ChatMessage> uiMessages,
                      Set<ProjectFile> changedFiles,
                      StopDetails stopDetails,
                      InteractionMode mode)
    {
        this(actionDescription,
             new ContextFragment.TaskFragment(contextManager, uiMessages, actionDescription, mode),
             changedFiles,
             stopDetails);
    }

    public TaskResult(IContextManager contextManager, String actionDescription,
                      List<ChatMessage> uiMessages,
                      Set<ProjectFile> changedFiles,
                      StopReason simpleReason,
                      InteractionMode mode)
    {
        this(actionDescription,
             new ContextFragment.TaskFragment(contextManager, uiMessages, actionDescription, mode),
             changedFiles,
             new StopDetails(simpleReason));
    }

    public TaskResult(IContextManager contextManager, String actionDescription,
                      List<ChatMessage> uiMessages,
                      Set<ProjectFile> changedFiles,
                      StopDetails stopDetails)
    {
        this(contextManager, actionDescription, uiMessages, changedFiles, stopDetails, InteractionMode.UNKNOWN);
    }

    public TaskResult(IContextManager contextManager, String actionDescription,
                      List<ChatMessage> uiMessages,
                      Set<ProjectFile> changedFiles,
                      StopReason simpleReason)
    {
        this(contextManager, actionDescription, uiMessages, changedFiles, simpleReason, InteractionMode.UNKNOWN);
    }

    /**
     * Enum representing the reason a CodeAgent session concluded.
     */
    public enum StopReason {
        /**
         * The agent successfully completed the goal.
         */
        SUCCESS,
        /**
         * The user interrupted the session.
         */
        INTERRUPTED,
        /**
         * The LLM returned an error after retries.
         */
        LLM_ERROR,
        /**
         * The LLM returned an empty or blank response after retries.
         */
        EMPTY_RESPONSE,
        /**
         * The LLM response could not be parsed after retries.
         */
        PARSE_ERROR,
        /**
         * Applying edits failed after retries.
         */
        APPLY_ERROR,
        /**
         * Build errors occurred and were not improving after retries.
         */
        BUILD_ERROR,
        /**
         * The LLM attempted to edit a read-only file.
         */
        READ_ONLY_EDIT,
        /**
         * Unable to write new file contents
         */
        IO_ERROR,
        /**
         * the LLM called answer() but did not provide a result
         */
        SEARCH_INVALID_ANSWER,
        /**
         * the LLM determined that it was not possible to fulfil the request
         */
        LLM_ABORTED,
    }

    public record StopDetails(StopReason reason, String explanation) {
        public StopDetails(StopReason reason) {
            this(reason, "");
        }

        @Override
        public String toString() {
            if (explanation.isEmpty()) {
                return reason.toString();
            }
            return "%s:\n%s".formatted(reason.toString(), explanation);
        }
    }
}
