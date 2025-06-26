package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class GitWorkflowService {
    private static final Logger logger = LogManager.getLogger(GitWorkflowService.class);

    public record CommitResult(String commitId, String firstLine) {
    }

    private final ContextManager contextManager;
    private final GitRepo repo;

    public GitWorkflowService(ContextManager contextManager)
    {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager");
        this.repo = (GitRepo) Objects.requireNonNull(
                contextManager.getProject().getRepo(), "repo cannot be null");
    }

    /**
     * Synchronously commit the given files.  If {@code files} is empty, commit
     * all modified files.  If {@code rawMessage} is null/blank, a suggestion
     * will be generated (may still be blank).  Comment lines (# …) are removed.
     */
    public CommitResult commit(List<ProjectFile> files,
                               @Nullable String rawMessage) throws GitAPIException
    {
        var filesToCommit = files.isEmpty()
                            ? repo.getModifiedFiles()
                                    .stream()
                                    .map(GitRepo.ModifiedFile::file)
                                    .toList()
                            : files;

        if (filesToCommit.isEmpty()) {
            throw new IllegalStateException("No files to commit.");
        }

        String msg = normaliseMessage(rawMessage);
        if (msg.isBlank()) {
            // suggestCommitMessage can throw RuntimeException if diffing fails
            // or InterruptedException occurs. Let it propagate.
            msg = suggestCommitMessage(filesToCommit);
        }

        if (msg.isBlank()) {
            throw new IllegalStateException("No commit message available after attempting suggestion.");
        }

        String sha = repo.commitFiles(filesToCommit, msg);
        var first = msg.contains("\n") ? msg.substring(0, msg.indexOf('\n'))
                                       : msg;
        return new CommitResult(sha, first);
    }

    /**
     * Background helper that returns a suggestion or empty string.
     * The caller decides on threading; no Swing here.
     * Can throw RuntimeException if diffing fails or InterruptedException occurs.
     */
    public String suggestCommitMessage(List<ProjectFile> files)
    {
        String diff;
        try {
            diff = files.isEmpty()
                   ? repo.diff()
                   : repo.diffFiles(files);
        } catch (GitAPIException e) {
            logger.error("Git diff operation failed while suggesting commit message", e);
            throw new RuntimeException("Failed to generate diff for commit message suggestion", e);
        }

        if (diff.isBlank()) {
            return "";
        }

        var messages = CommitPrompts.instance.collectMessages(contextManager.getProject(), diff);
        if (messages.isEmpty()) {
            return "";
        }

        Llm.StreamingResult result;
        try {
            result = contextManager.getLlm(
                            contextManager.getService().quickestModel(),
                            "Infer commit message")
                    .sendRequest(messages);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Commit message suggestion was interrupted", ie);
        }

        return result.error() == null ? result.text() : "";
    }

    private static String normaliseMessage(@Nullable String raw)
    {
        if (raw == null) return "";
        return Arrays.stream(raw.split("\n"))
                .filter(l -> !l.trim().startsWith("#"))
                .collect(Collectors.joining("\n"))
                .trim();
    }
}
