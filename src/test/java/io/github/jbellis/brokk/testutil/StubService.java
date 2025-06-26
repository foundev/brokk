package io.github.jbellis.brokk.testutil;

import dev.langchain4j.model.chat.DisabledStreamingChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.io.IOException;

public final class StubService extends Service {

    public StubService(IProject project) {
        super(project);
    }

    @Override
    protected void fetchAvailableModels(MainProject.DataRetentionPolicy policy,
			      Map<String, String> locationsTarget,
			      Map<String, Map<String, Object>> infoTarget) throws IOException
    { }

    @Override
    public String nameOf(@Nullable StreamingChatLanguageModel model) { return "stub-model"; }

    @Override
    public boolean isLazy(@Nullable StreamingChatLanguageModel model) { return false; }

    @Override
    public boolean isReasoning(@Nullable StreamingChatLanguageModel model) { return false; }

    @Override
    public boolean requiresEmulatedTools(@Nullable StreamingChatLanguageModel model) { return false; }

    @Override
    public boolean supportsJsonSchema(@Nullable StreamingChatLanguageModel model) { return true; }

    @Override
    public StreamingChatLanguageModel getModel(String modelName, Service.ReasoningLevel reasoningLevel) {
        return new DisabledStreamingChatLanguageModel();
    }
}
