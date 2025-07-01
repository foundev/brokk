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

import javax.swing.text.JTextComponent;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Provides file and symbol completions for the InstructionsPanel.
 */
public class InstructionCompletionProvider extends DefaultCompletionProvider {
    private final ContextManager contextManager;
    private final IProject project;

    public InstructionCompletionProvider(ContextManager cm) {
        this.contextManager = requireNonNull(cm);
        this.project = requireNonNull(cm.getProject());
    }

    @Override
    public String getAlreadyEnteredText(JTextComponent comp) {
        String text = comp.getText();
        int caretPos = comp.getCaretPosition();
        
        // Find the last whitespace or newline before the caret
        int lastBreak = caretPos - 1;
        while (lastBreak >= 0) {
            char c = text.charAt(lastBreak);
            if (Character.isWhitespace(c)) {
                lastBreak++;
                break;
            }
            lastBreak--;
        }
        if (lastBreak < 0) lastBreak = 0;
        
        return text.substring(lastBreak, caretPos);
    }

    @Override
    public List<Completion> getCompletions(JTextComponent tc) {
        String pattern = getAlreadyEnteredText(tc).trim();
        if (pattern.length() < 2) {
            return List.of();
        }

        // Get analyzer and check if it's ready
        IAnalyzer analyzer;
        try {
            analyzer = contextManager.getAnalyzer();
        } catch (InterruptedException e) {
            // If interrupted while getting analyzer, return empty completions
            Thread.currentThread().interrupt(); // Restore interrupt status
            return List.of();
        }
        Collection<CodeUnit> symbols = analyzer.isEmpty() ? List.of() : analyzer.getAllDeclarations();
        
        // Get all project files
        var files = project.getAllFiles();
        
        // Score and convert files to completions
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
        
        // Score and convert symbols to completions
        var symbolCompletions = Completions.scoreShortAndLong(
            pattern,
            symbols,
            CodeUnit::identifier,   // extractShort
            CodeUnit::fqName,      // extractLong
            cu -> {                // tiebreaker based on type
                if (cu.isClass()) return 0;
                if (cu.isFunction()) return 1;
                return 2; // fields
            },
            cu -> {                // toCompletion
                String shortName = cu.identifier();
                String fqName = cu.fqName();
                String description = cu.isClass() ? "class" : 
                                   cu.isFunction() ? "method" : "field";
                return new ShorthandCompletion(this, shortName, fqName, 
                                             description + ": " + fqName);
            }
        );
        
        // Combine results and limit to 50
        return Stream.concat(fileCompletions.stream(), symbolCompletions.stream())
                .limit(50)
                .map(sc -> (Completion) sc)
                .collect(Collectors.toList());
    }
}
