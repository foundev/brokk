package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Provides file and symbol completions for the InstructionsPanel.
 */
public class InstructionCompletionProvider extends DefaultCompletionProvider {
    private final ContextManager contextManager;
    private final IProject project;
    private @Nullable AutoCompletion autoCompletion;
    private String lastPattern = "";
    private List<Completion> currentCompletions = Collections.emptyList();
    private final AtomicLong completionGeneration = new AtomicLong(0);


    public InstructionCompletionProvider(ContextManager cm) {
        this.contextManager = requireNonNull(cm);
        this.project = requireNonNull(cm.getProject());
    }

    public void setAutoCompletion(AutoCompletion ac) {
        this.autoCompletion = ac;
    }

    @Override
    public String getAlreadyEnteredText(JTextComponent comp) {
        return AutoCompleteUtil.getWordBeforeCaret(comp);
    }

    @Override
    public List<Completion> getCompletions(JTextComponent tc) {
        String pattern = getAlreadyEnteredText(tc).trim();
        if (pattern.length() < 2) {
            lastPattern = "";
            currentCompletions = Collections.emptyList();
            return currentCompletions;
        }

        if (!pattern.equals(lastPattern)) {
            lastPattern = pattern;
            currentCompletions = Collections.emptyList(); // Clear immediately for new pattern
            final long generation = completionGeneration.incrementAndGet();

            contextManager.submitBackgroundTask("Autocomplete", () -> {
                IAnalyzer analyzer;
                try {
                    analyzer = contextManager.getAnalyzer();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                // If a new request has come in while we were waiting, abort
                if (generation != completionGeneration.get()) {
                    return;
                }

                Collection<CodeUnit> symbols = analyzer.isEmpty() ? List.of() : analyzer.getAllDeclarations();
                var files = project.getAllFiles();

                var fileCompletions = Completions.scoreShortAndLong(
                        pattern,
                        files,
                        pf -> pf.getFileName(), // extractShort
                        ProjectFile::toString,  // extractLong
                        pf -> 0,               // tiebreaker
                        pf -> {                // toCompletion
                            String shortName = pf.getFileName();
                            String relPath = pf.toString();
                            String fullPath = pf.absPath().toString();
                            return new ShorthandCompletion(this, shortName, relPath, fullPath);
                        }
                );

                var symbolCompletions = Completions.completeSymbols(pattern, symbols).stream()
                        .map(cu -> {
                            String shortName = cu.identifier();
                            String fqName = cu.fqName();
                            String description = cu.isClass() ? "class" :
                                    cu.isFunction() ? "method" : "field";
                            return new ShorthandCompletion(this, shortName, fqName,
                                    description + ": " + fqName);
                        })
                        .toList();

                var newCompletions = Stream.concat(fileCompletions.stream(), symbolCompletions.stream())
                        .limit(50)
                        .map(sc -> (Completion) sc)
                        .collect(Collectors.toList());

                SwingUtilities.invokeLater(() -> {
                    // Final check: is this still the latest request and does the text still match?
                    if (generation == completionGeneration.get() && pattern.equals(getAlreadyEnteredText(tc).trim())) {
                        currentCompletions = newCompletions;
                        if (autoCompletion != null && !newCompletions.isEmpty()) {
                            autoCompletion.doCompletion();
                        }
                    }
                });
            });
        }

        return currentCompletions;
    }
}
