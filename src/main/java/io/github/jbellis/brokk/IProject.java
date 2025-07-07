package io.github.jbellis.brokk;

import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.Set;

public interface IProject extends AutoCloseable {

    default IGitRepo getRepo() {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets the set of Brokk Language enums configured for the project.
     * @return A set of Language enums.
     */
    default Set<Language> getAnalyzerLanguages() {
        throw new UnsupportedOperationException();
    }

    default Path getRoot() {
        throw new UnsupportedOperationException();
    }

    /**
     * All files in the project, including decompiled dependencies that are not in the git repo.
     */
    default Set<ProjectFile> getAllFiles() {
        return Set.of();
    }

    /**
     * Gets the structured build details inferred by the BuildAgent.
     * @return BuildDetails record, potentially BuildDetails.EMPTY if not found or on error.
     */
    default BuildAgent.BuildDetails loadBuildDetails() {
        return BuildAgent.BuildDetails.EMPTY; // Default implementation returns empty
    }

    default MainProject.DataRetentionPolicy getDataRetentionPolicy() {
        return MainProject.DataRetentionPolicy.MINIMAL;
    }

    default String getStyleGuide() {
        return "";
    }

    default String getReviewGuide() {
        throw new UnsupportedOperationException();
    }

    default void saveReviewGuide(String reviewGuide) {
        throw new UnsupportedOperationException();
    }

    default Path getMasterRootPathForConfig() {
        throw new UnsupportedOperationException();
    }

    default IProject getParent() {
        return this;
    }

    default boolean hasGit() {
        return false;
    }

    default void saveBuildDetails(BuildAgent.BuildDetails details) {
        throw new UnsupportedOperationException();
    }

    default CompletableFuture<BuildAgent.BuildDetails> getBuildDetailsFuture() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    default Service.ModelConfig getArchitectModelConfig() {
        throw new UnsupportedOperationException();
    }

    default Service.ModelConfig getCodeModelConfig() {
        throw new UnsupportedOperationException();
    }

    default Service.ModelConfig getAskModelConfig() {
        throw new UnsupportedOperationException();
    }

    default Service.ModelConfig getSearchModelConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    default void close() {}

    default boolean hasBuildDetails() {
        return false;
    }

    default void saveStyleGuide(String styleGuide) {
        throw new UnsupportedOperationException();
    }

    default CpgRefresh getAnalyzerRefresh() {
        throw new UnsupportedOperationException();
    }

    default BuildAgent.BuildDetails awaitBuildDetails() {
        throw new UnsupportedOperationException();
    }

    default void setAnalyzerRefresh(CpgRefresh cpgRefresh) {}

    default boolean isDataShareAllowed() {
        return false;
    }

    default void setDataRetentionPolicy(MainProject.DataRetentionPolicy selectedPolicy) {}

    default java.awt.Rectangle getPreviewWindowBounds() { throw new UnsupportedOperationException(); }
    default void savePreviewWindowBounds(javax.swing.JFrame frame) { throw new UnsupportedOperationException(); }
    default java.awt.Rectangle getDiffWindowBounds() { throw new UnsupportedOperationException(); }
    default void saveDiffWindowBounds(javax.swing.JFrame frame) { throw new UnsupportedOperationException(); }
    default java.awt.Rectangle getOutputWindowBounds() { throw new UnsupportedOperationException(); }
    default void saveOutputWindowBounds(javax.swing.JFrame frame) { throw new UnsupportedOperationException(); }
    default java.util.Optional<java.awt.Rectangle> getMainWindowBounds() { throw new UnsupportedOperationException(); }
    default void saveMainWindowBounds(javax.swing.JFrame frame) { throw new UnsupportedOperationException(); }

    default int getHorizontalSplitPosition() { return -1;}
    default void saveHorizontalSplitPosition(int position) {throw new UnsupportedOperationException(); }
    default int getLeftVerticalSplitPosition() { return -1;}
    default void saveLeftVerticalSplitPosition(int position) {throw new UnsupportedOperationException(); }
    default int getRightVerticalSplitPosition() { return -1;}
    default void saveRightVerticalSplitPosition(int position) {throw new UnsupportedOperationException(); }

    default List<String> loadTextHistory() { return List.of(); }
    default List<String> addToInstructionsHistory(String item, int maxItems) { throw new UnsupportedOperationException(); }

    // Git specific info
    default boolean isGitHubRepo() { return false; }
    default boolean isGitIgnoreSet() { return false; }

    default void setArchitectModelConfig(Service.ModelConfig modelConfig) {
        throw new UnsupportedOperationException();
    }

    default void setCodeModelConfig(Service.ModelConfig modelConfig) {
        throw new UnsupportedOperationException();
    }

    default void setAskModelConfig(Service.ModelConfig modelConfig) {
        throw new UnsupportedOperationException();
    }

    default void setSearchModelConfig(Service.ModelConfig modelConfig) {
        throw new UnsupportedOperationException();
    }

    default String getCommitMessageFormat() {
        throw new UnsupportedOperationException();
    }

    default CodeAgentTestScope getCodeAgentTestScope() {
        throw new UnsupportedOperationException();
    }

    default void setCommitMessageFormat(String text) {}

    default void setCodeAgentTestScope(CodeAgentTestScope selectedScope) {}

    default void setAnalyzerLanguages(Set<Language> languages) {}

    default ArchitectAgent.ArchitectOptions getArchitectOptions() {
        throw new UnsupportedOperationException();
    }

    default boolean getArchitectRunInWorktree() {
        throw new UnsupportedOperationException();
    }

    default void setArchitectOptions(ArchitectAgent.ArchitectOptions options, boolean runInWorktree) {
        throw new UnsupportedOperationException();
    }

    // New methods for the IssueProvider record
    default io.github.jbellis.brokk.IssueProvider getIssuesProvider() { // Method name clash is intentional record migration
        throw new UnsupportedOperationException();
    }

    default void setIssuesProvider(io.github.jbellis.brokk.IssueProvider provider) {
        throw new UnsupportedOperationException();
    }

    default SessionManager getSessionManager() {
        throw new UnsupportedOperationException();
    }

    enum CpgRefresh {
        AUTO,
        ON_RESTART,
        MANUAL,
        UNSET
    }

    enum CodeAgentTestScope {
        ALL, WORKSPACE;

        @Override
        public String toString() {
            return switch (this) {
                case ALL -> "Run All Tests";
                case WORKSPACE -> "Run Tests in Workspace";
            };
        }

        public static CodeAgentTestScope fromString(@Nullable String value, CodeAgentTestScope defaultScope) {
            if (value == null || value.isBlank()) return defaultScope;
            try {
                return CodeAgentTestScope.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return defaultScope;
            }
        }
    }
}
