package io.github.jbellis.brokk.context;

import dev.langchain4j.data.message.*;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.util.Messages;
import org.fife.ui.rsyntaxtextarea.FileTypeUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.jbellis.brokk.AnalyzerUtil;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface ContextFragment {
    // Static counter for all fragments
    // TODO reset this on new session (when we have sessions)
    AtomicInteger nextId = new AtomicInteger(1);

    /**
     * Gets the current max fragment ID for serialization purposes
     */
    static int getCurrentMaxId() {
        return nextId.get();
    }

    /**
     * Sets the next fragment ID value (used during deserialization)
     */
    static void setNextId(int value) {
        if (value > nextId.get()) {
            nextId.set(value);
        }
    }

    /**
     * Unique identifier for this fragment
     */
    int id();

    /**
     * short description in history
     */
    String shortDescription();

    /**
     * longer description displayed in context table
     */
    String description();

    /**
     * raw content for preview
     */
    String text() throws IOException, InterruptedException;

    /**
     * content formatted for LLM
     */
    String format() throws IOException, InterruptedException;

    /**
     * Indicates if the fragment's content can change based on project/file state.
     */
    boolean isDynamic();

    /**
     * for Quick Context LLM
     */
    default String formatSummary() {
        return description();
    }

    default boolean isText() {
        return true;
    }

    default Image image() throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * code sources found in this fragment
     */
    Set<CodeUnit> sources();

    /**
     * Returns all repo files referenced by this fragment.
     * This is used when we *just* want to manipulate or show actual files,
     * rather than the code units themselves.
     */
    Set<ProjectFile> files();

    String syntaxStyle();

    /**
     * If false, the classes returned by sources() will be pruned from AutoContext suggestions.
     * (Corollary: if sources() always returns empty, this doesn't matter.)
     */
    default boolean isEligibleForAutoContext() {
        return true;
    }

    static Set<ProjectFile> parseProjectFiles(String text, IProject project) {
        var exactMatches = project.getAllFiles().stream().parallel()
                .filter(f -> text.contains(f.toString()))
                .collect(Collectors.toSet());
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }

        return project.getAllFiles().stream().parallel()
                .filter(f -> text.contains(f.getFileName()))
                .collect(Collectors.toSet());
    }

    sealed interface PathFragment extends ContextFragment
            permits ProjectPathFragment, GitFileFragment, ExternalPathFragment, ImageFileFragment
    {
        BrokkFile file();

        @Override
        default Set<ProjectFile> files() {
            BrokkFile bf = file();
            if (bf instanceof ProjectFile pf) {
                return Set.of(pf);
            }
            return Set.of();
        }

        @Override
        default String text() throws IOException {
            return file().read();
        }

        @Override
        default String syntaxStyle() {
            return FileTypeUtil.get().guessContentType(file().absPath().toFile());
        }

        @Override
        default String format() throws IOException {
            // PathFragments are dynamic, but their text() doesn't need the analyzer here
            // as it reads directly from the file.
            return """
                    <file path="%s" fragmentid="%d">
                    %s
                    </file>
                    """.stripIndent().formatted(file().toString(), id(), text());
        }

        @Override
        default boolean isDynamic() {
            return true; // File content can change
        }

        static String formatSummary(BrokkFile file) {
            return "<file source=\"%s\" />".formatted(file);
        }
    }

    record ProjectPathFragment(ProjectFile file, int id, IContextManager contextManager) implements PathFragment {

        public ProjectPathFragment(ProjectFile file, IContextManager contextManager) {
            this(file, nextId.getAndIncrement(), contextManager);
        }

        public static ProjectPathFragment withId(ProjectFile file, int existingId, IContextManager contextManager) {
            // Update the counter if needed to avoid ID conflicts
            if (existingId >= nextId.get()) {
                nextId.set(existingId + 1);
            }
            return new ProjectPathFragment(file, existingId, contextManager);
        }

        @Override
        public String shortDescription() {
            return file().getFileName();
        }

        @Override
        public Set<ProjectFile> files() {
            return Set.of(file);
        }

        @Override
        public String description() {
            if (file.getParent().equals(Path.of(""))) {
                return file.getFileName();
            }
            return "%s [%s]".formatted(file.getFileName(), file.getParent());
        }

        @Override
        public String formatSummary() {
            var analyzer = contextManager.getAnalyzerUninterrupted();
            var summary = analyzer.getSkeletons(file).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .collect(Collectors.joining("\n"));
            if (summary.isBlank()) {
                // this also handles the analyzer.isEmpty case
                return PathFragment.formatSummary(file);
            }
            return """
                   <file source="%s" summarized=true>
                   %s
                   </file>
                   """.formatted(file, summary);
        }

        @Override
        public Set<CodeUnit> sources() {
            return contextManager.getAnalyzerUninterrupted().getDeclarationsInFile(file);
        }

        @Override
        public String toString() {
            return "ProjectPathFragment('%s')".formatted(file);
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }
    }

    /**
     * Represents a specific revision of a ProjectFile from Git history.
     */
    record GitFileFragment(ProjectFile file, String revision, String content, int id) implements PathFragment {

        public GitFileFragment(ProjectFile file, String revision, String content) {
            this(file, revision, content, nextId.getAndIncrement());
        }

        public static GitFileFragment withId(ProjectFile file, String revision, String content, int existingId) {
            // Update the counter if needed to avoid ID conflicts
            if (existingId >= nextId.get()) {
                nextId.set(existingId + 1);
            }
            return new GitFileFragment(file, revision, content, existingId);
        }

        private String shortRevision() {
            return (revision.length() > 7) ? revision.substring(0, 7) : revision;
        }

        @Override
        public String shortDescription() {
            return "%s @%s".formatted(file().getFileName(), shortRevision());
        }

        @Override
        public String description() {
            var parentDir = file.getParent();
            return parentDir.equals(Path.of(""))
                   ? shortDescription()
                   : "%s [%s]".formatted(shortDescription(), parentDir);
        }

        @Override
        public Set<CodeUnit> sources() {
            // Treat historical content as potentially different from current; don't claim sources
            return Set.of();
        }

        @Override
        public String text() {
            return content;
        }

        @Override
        public String format() throws IOException {
            return """
                    <file path="%s" revision="%s">
                    %s
                    </file>
                    """.stripIndent().formatted(file().toString(), revision(), text());
        }

        @Override
        public boolean isDynamic() { // Removed 'default'
            return false; // Content is fixed to a revision
        }

        @Override
        public String formatSummary() {
            return PathFragment.formatSummary(file);
        }

        // Removed custom hashCode to rely on the default record implementation,
        // as the Scala compiler might be having issues with the override.
        // The default hashCode is based on all record components.
        // If a specific hashCode behavior (like always returning 0) was intended,
        // this needs to be revisited, along with a corresponding equals().

        @Override
        public String toString() {
            return "GitFileFragment('%s' @%s)".formatted(file, shortRevision());
        }
    }


    record ExternalPathFragment(ExternalFile file, int id, IContextManager contextManager) implements PathFragment {

        public ExternalPathFragment(ExternalFile file, IContextManager contextManager) {
            this(file, nextId.getAndIncrement(), contextManager);
        }

        public static ExternalPathFragment withId(ExternalFile file, int existingId, IContextManager contextManager) {
            // Update the counter if needed to avoid ID conflicts
            if (existingId >= nextId.get()) {
                nextId.set(existingId + 1);
            }
            return new ExternalPathFragment(file, existingId, contextManager);
        }

        @Override
        public String shortDescription() {
            return description();
        }

        @Override
        public String description() {
            return file.toString();
        }

        @Override
        public Set<CodeUnit> sources() {
            return Set.of();
        }

        @Override
        public String formatSummary() {
            return PathFragment.formatSummary(file);
        }
    }

    /**
     * Represents an image file, either from the project or external.
     */
    record ImageFileFragment(BrokkFile file, int id, IContextManager contextManager) implements PathFragment {

        public ImageFileFragment(BrokkFile file, IContextManager contextManager) {
            this(file, nextId.getAndIncrement(), contextManager);
            assert !file.isText() : "ImageFileFragment should only be used for non-text files";
        }

        public static ImageFileFragment withId(BrokkFile file, int existingId, IContextManager contextManager) {
            assert !file.isText() : "ImageFileFragment should only be used for non-text files";
            // Update the counter if needed to avoid ID conflicts
            if (existingId >= nextId.get()) {
                nextId.set(existingId + 1);
            }
            return new ImageFileFragment(file, existingId, contextManager);
        }

        @Override
        public String shortDescription() {
            return file().getFileName();
        }

        @Override
        public String description() {
            if (file instanceof ProjectFile pf && !pf.getParent().equals(Path.of(""))) {
                return "%s [%s]".formatted(file.getFileName(), pf.getParent());
            }
            return file.toString(); // For ExternalFile or root ProjectFile
        }

        @Override
        public boolean isText() {
            return false;
        }

        @Override
        public String text() {
            // return this text tu support ContextMenu Fragment > Copy
            return "[Image content provided out of band]";
        }

        @Override
        public Image image() throws IOException {
            return javax.imageio.ImageIO.read(file.absPath().toFile());
        }

        @Override
        public Set<CodeUnit> sources() {
            return Set.of();
        }

        @Override
        public Set<ProjectFile> files() {
            return (file instanceof ProjectFile pf) ? Set.of(pf) : Set.of();
        }

        @Override
        public String format() {
            // Format for LLM, indicating image content (similar to PasteImageFragment)
            return """
                    <file path="%s" fragmentid="%d">
                    [Image content provided out of band]
                    </file>
                    """.stripIndent().formatted(file().toString(), id());
        }

        @Override
        public boolean isDynamic() { // Removed 'default'
            return true; // Image file on disk could change
        }

        @Override
        public String formatSummary() {
            return PathFragment.formatSummary(file);
        }

        @Override
        public String toString() {
            return "ImageFileFragment('%s')".formatted(file);
        }
    }

    static PathFragment toPathFragment(BrokkFile bf, IContextManager contextManager) {
        if (bf.isText()) {
            if (bf instanceof ProjectFile pf) {
                return new ProjectPathFragment(pf, contextManager);
            } else if (bf instanceof ExternalFile ext) {
                return new ExternalPathFragment(ext, contextManager);
            }
        } else {
            // If it's not text, treat it as an image
            return new ImageFileFragment(bf, contextManager);
        }
        // Should not happen if bf is ProjectFile or ExternalFile
        throw new IllegalArgumentException("Unsupported BrokkFile subtype: " + bf.getClass().getName());
    }

    public static abstract class VirtualFragment implements ContextFragment {
        private final int id;
        protected final transient IContextManager contextManager;

        public VirtualFragment(IContextManager contextManager) {
            this.id = nextId.getAndIncrement();
            this.contextManager = contextManager;
        }

        protected VirtualFragment(int existingId, IContextManager contextManager) {
            this.id = existingId;
            this.contextManager = contextManager;
            // Update the counter if needed to avoid ID conflicts
            if (existingId >= nextId.get()) {
                nextId.set(existingId + 1);
            }
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public String format() throws IOException, InterruptedException {
            return """
                    <fragment description="%s" fragmentid="%d">
                    %s
                    </fragment>
                    """.stripIndent().formatted(description(), id(), text());
        }

        @Override
        public String shortDescription() {
            assert !description().isEmpty();
            // lowercase the first letter in description()
            return description().substring(0, 1).toLowerCase() + description().substring(1);
        }

        @Override
        public Set<ProjectFile> files() {
            try {
                return parseProjectFiles(text(), contextManager.getProject());
            } catch (IOException | InterruptedException e) {
                return Set.of(); // Or handle error appropriately
            }
        }

        @Override
        public Set<CodeUnit> sources() {
            return Set.of();
        }

        @Override
        public String formatSummary() {
            return "<fragment description=\"%s\" />".formatted(description());
        }

        @Override
        public abstract String text() throws IOException, InterruptedException;

        // Override equals and hashCode for proper comparison, especially for EMPTY
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VirtualFragment that = (VirtualFragment) o;
            return id() == that.id();
        }

        @Override
        public int hashCode() {
            // Use id for hashCode
            return Integer.hashCode(id());
        }
    }

    public static class StringFragment extends VirtualFragment {
        private final String text;
        private final String description;
        private final String syntaxStyle;

        public StringFragment(IContextManager contextManager, String text, String description, String syntaxStyle) {
            super(contextManager);
            this.syntaxStyle = syntaxStyle;
            assert text != null;
            assert description != null;
            this.text = text;
            this.description = description;
        }

        public StringFragment(int existingId, IContextManager contextManager, String text, String description, String syntaxStyle) {
            super(existingId, contextManager);
            this.syntaxStyle = syntaxStyle;
            assert text != null;
            assert description != null;
            this.text = text;
            this.description = description;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public String syntaxStyle() {
            return syntaxStyle;
        }

        @Override
        public String toString() {
            return "StringFragment('%s')".formatted(description);
        }
    }

    // FIXME SearchFragment does not preserve the tool calls output that the user sees during
    // the search, I think we need to add a messages parameter and pass them to super();
    // then we'd also want to override format() to keep it out of what the LLM sees
    public static class SearchFragment extends TaskFragment {
        private static final long serialVersionUID = 5L;
        private final Set<CodeUnit> sources; // This is pre-computed, so SearchFragment is not dynamic in content

        public SearchFragment(IContextManager contextManager, String sessionName, List<ChatMessage> messages, Set<CodeUnit> sources) {
            super(contextManager, messages, sessionName);
            assert sources != null;
            this.sources = sources;
        }


        @Override
        public Set<CodeUnit> sources() {
            return sources; // Return pre-computed sources
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public Set<ProjectFile> files() {
            // SearchFragment sources are pre-computed
            return sources().stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String formatSummary() {
            try {
                return format(); // full search result
            } catch (IOException | InterruptedException e) {
                return description(); // fallback
            }
        }

        // --- Custom Serialization using Proxy Pattern ---
        // SearchFragment extends TaskFragment, which has its own proxy for messages.
        // We only need to handle SearchFragment's own fields here. TaskFragment's state
        // should be handled by its proxy during the serialization process.

        @java.io.Serial
        private Object writeReplace() {
            return new SerializationProxy(this);
        }

        @java.io.Serial
        private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
            // This method should not be called if writeReplace is used.
            throw new java.io.NotSerializableException("SearchFragment must be serialized via SerializationProxy");
        }

        private static class SerializationProxy implements java.io.Serializable {
            @java.io.Serial
            private static final long serialVersionUID = 41L;

            private final String serializedMessages; // Store messages as JSON string
            private final String sessionName;
            private final Set<CodeUnit> sources;
            // IContextManager is transient and will be null after deserialization, needs to be set by Context
            private transient IContextManager contextManager;


            SerializationProxy(SearchFragment fragment) {
                this.contextManager = fragment.contextManager;
                // Store the class name of the parser
                this.sessionName = fragment.description();
                this.serializedMessages = ChatMessageSerializer.messagesToJson(fragment.messages());
                this.sources = fragment.sources;
            }

            /**
             * Reconstruct the TaskFragment instance after the SerializationProxy is deserialized.
             */
            @java.io.Serial
            private Object readResolve() throws java.io.ObjectStreamException {
                List<ChatMessage> deserializedMessages = ChatMessageDeserializer.messagesFromJson(serializedMessages);
                // contextManager will be null here, needs to be injected by Context.fromJson
                return new SearchFragment(null, sessionName, deserializedMessages, sources);
            }
        }
    }

    public static abstract class PasteFragment extends ContextFragment.VirtualFragment {
        protected transient Future<String> descriptionFuture;

        public PasteFragment(IContextManager contextManager, Future<String> descriptionFuture) {
            super(contextManager);
            this.descriptionFuture = descriptionFuture;
        }

        public PasteFragment(int existingId, IContextManager contextManager, Future<String> descriptionFuture) {
            super(existingId, contextManager);
            this.descriptionFuture = descriptionFuture;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public String description() {
            if (descriptionFuture.isDone()) {
                try {
                    return "Paste of " + descriptionFuture.get();
                } catch (Exception e) {
                    return "(Error summarizing paste)";
                }
            }
            return "(Summarizing. This does not block LLM requests)";
        }

        @Override
        public String toString() {
            return "PasteFragment('%s')".formatted(description());
        }
    }

    public static class PasteTextFragment extends PasteFragment {
        private final String text;

        public PasteTextFragment(IContextManager contextManager, String text, Future<String> descriptionFuture) {
            super(contextManager, descriptionFuture);
            assert text != null;
            assert descriptionFuture != null;
            this.text = text;
        }

        public PasteTextFragment(int existingId, IContextManager contextManager, String text, Future<String> descriptionFuture) {
            super(existingId, contextManager, descriptionFuture);
            assert text != null;
            assert descriptionFuture != null;
            this.text = text;
        }

        @Override
        public String syntaxStyle() {
            // TODO infer from contents
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }

        @Override
        public String text() {
            return text;
        }
    }

    public static class PasteImageFragment extends PasteFragment {
        private final Image image;

        public PasteImageFragment(IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            super(contextManager, descriptionFuture);
            assert image != null;
            assert descriptionFuture != null;
            this.image = image;
        }

        public PasteImageFragment(int existingId, IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            super(existingId, contextManager, descriptionFuture);
            assert image != null;
            assert descriptionFuture != null;
            this.image = image;
        }

        @Override
        public boolean isText() {
            return false;
        }

        @Override
        public String text() {
            // return this text tu support ContextMenu Fragment > Copy
            return "[Image content provided out of band]";
        }

        @Override
        public Image image() {
            return image;
        }

        @Override
        public String syntaxStyle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String format() {
            return """
              <fragment description="%s" fragmentid="%d">
              %s
              </fragment>
              """.stripIndent().formatted(description(), id(), text());
        }

        @Override
        public Set<ProjectFile> files() {
            return Set.of();
        }
    }

    public static class StacktraceFragment extends VirtualFragment {
        private final Set<CodeUnit> sources; // Pre-computed, so not dynamic in content
        private final String original;
        private final String exception;
        private final String code; // Pre-computed code parts

        public StacktraceFragment(IContextManager contextManager, Set<CodeUnit> sources, String original, String exception, String code) {
            super(contextManager);
            assert sources != null;
            assert original != null;
            assert exception != null;
            assert code != null;
            this.sources = sources;
            this.original = original;
            this.exception = exception;
            this.code = code;
        }

        public StacktraceFragment(int existingId, IContextManager contextManager, Set<CodeUnit> sources, String original, String exception, String code) {
            super(existingId, contextManager);
            assert sources != null;
            assert original != null;
            assert exception != null;
            assert code != null;
            this.sources = sources;
            this.original = original;
            this.exception = exception;
            this.code = code;
        }

        @Override
        public String text() {
            return original + "\n\nStacktrace methods in this project:\n\n" + code;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public Set<CodeUnit> sources() {
            return sources; // Return pre-computed sources
        }

        @Override
        public Set<ProjectFile> files() {
            // StacktraceFragment sources are pre-computed
            return sources().stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String description() {
            return "stacktrace of " + exception;
        }

        @Override
        public String formatSummary() {
            try {
                return format(); // full source
            } catch (IOException | InterruptedException e) {
                return description(); // fallback
            }
        }

        @Override
        public String syntaxStyle() {
            if (sources.isEmpty()) {
                return SyntaxConstants.SYNTAX_STYLE_NONE;
            }
            var firstClass = sources.iterator().next();
            return firstClass.source().getSyntaxStyle();
        }
    }

    static String toClassname(String methodname) {
        int lastDot = methodname.lastIndexOf('.');
        if (lastDot == -1) {
            return methodname;
        }
        return methodname.substring(0, lastDot);
    }

    public static class UsageFragment extends VirtualFragment {
        private final String targetIdentifier;

        public UsageFragment(IContextManager contextManager, String targetIdentifier) {
            super(contextManager);
            assert targetIdentifier != null && !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
        }

        public UsageFragment(int existingId, IContextManager contextManager, String targetIdentifier) {
            super(existingId, contextManager);
            assert targetIdentifier != null && !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
        }

        @Override
        public String text() throws InterruptedException {
            var analyzer = contextManager.getAnalyzer();
            if (analyzer == null || analyzer.isEmpty()) {
                return "Code intelligence not available to find usages for " + targetIdentifier;
            }
            List<CodeUnit> uses = analyzer.getUses(targetIdentifier);
            var result = AnalyzerUtil.processUsages(analyzer, uses);
            return result.code().isEmpty() ? "No relevant usages found for symbol: " + targetIdentifier : result.code();
        }

        @Override
        public boolean isDynamic() {
            return true;
        }

        @Override
        public Set<CodeUnit> sources() {
            IAnalyzer analyzer;
            try {
                analyzer = contextManager.getAnalyzer();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Set.of();
            }
            if (analyzer == null || analyzer.isEmpty()) {
                return Set.of();
            }
            // This might re-fetch uses, could be optimized if text() caches 'result'
            List<CodeUnit> uses = analyzer.getUses(targetIdentifier);
            var result = AnalyzerUtil.processUsages(analyzer, uses);
            return result.sources();
        }

        @Override
        public Set<ProjectFile> files() {
            IAnalyzer analyzer;
            try {
                analyzer = contextManager.getAnalyzer();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Set.of();
            }
            if (analyzer == null || analyzer.isEmpty()) {
                return Set.of();
            }
            return sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
        }

        @Override
        public String description() {
            return "Uses of %s".formatted(targetIdentifier);
        }

        @Override
        public String syntaxStyle() {
            // Syntax can vary based on the language of the usages.
            // Default to Java or try to infer from a source CodeUnit if available.
            // For simplicity, returning Java, but this could be improved.
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }

        public String targetIdentifier() {
            return targetIdentifier;
        }
    }

    public static class CallGraphFragment extends VirtualFragment {
        private final String methodName;
        private final int depth;
        private final boolean isCalleeGraph; // true for callees (OUT), false for callers (IN)

        public CallGraphFragment(IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            super(contextManager);
            assert methodName != null && !methodName.isBlank();
            assert depth > 0;
            this.methodName = methodName;
            this.depth = depth;
            this.isCalleeGraph = isCalleeGraph;
        }

        public CallGraphFragment(int existingId, IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            super(existingId, contextManager);
            assert methodName != null && !methodName.isBlank();
            assert depth > 0;
            this.methodName = methodName;
            this.depth = depth;
            this.isCalleeGraph = isCalleeGraph;
        }

        @Override
        public String text() throws InterruptedException {
            var analyzer = contextManager.getAnalyzer();
            if (analyzer == null || !analyzer.isCpg()) {
                return "Code intelligence not available for call graph of " + methodName;
            }
            Map<String, List<CallSite>> graphData;
            if (isCalleeGraph) {
                graphData = analyzer.getCallgraphFrom(methodName, depth);
            } else {
                graphData = analyzer.getCallgraphTo(methodName, depth);
            }

            if (graphData.isEmpty()) {
                return "No call graph available for " + methodName;
            }
            return AnalyzerUtil.formatCallGraph(graphData, methodName, isCalleeGraph);
        }

        @Override
        public boolean isDynamic() {
            return true;
        }

        @Override
        public Set<CodeUnit> sources() {
            IAnalyzer analyzer;
            try {
                analyzer = contextManager.getAnalyzer();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Set.of();
            }

            if (analyzer == null || analyzer.isEmpty()) {
                return Set.of();
            }
            // The primary source is the class containing the target method
            return analyzer.getDefinition(methodName)
                           .flatMap(CodeUnit::classUnit) // Get the containing class CodeUnit
                           .map(Set::of)
                           .orElse(Set.of());
        }

        @Override
        public Set<ProjectFile> files() {
            IAnalyzer analyzer;
            try {
                analyzer = contextManager.getAnalyzer();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Set.of();
            }
            if (analyzer == null || analyzer.isEmpty()) {
                return Set.of();
            }
            return sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
        }

        @Override
        public String description() {
            String type = isCalleeGraph ? "Callees" : "Callers";
            return "%s of %s (depth %d)".formatted(type, methodName, depth);
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_NONE; // Call graph is textual, not specific code language
        }

        public String getMethodName() { return methodName; }
        public int getDepth() { return depth; }
        public boolean isCalleeGraph() { return isCalleeGraph; }
    }

    public enum SummaryType {
        CLASS_SKELETON, // Summary for a list of FQ class names
        FILE_SKELETONS  // Summaries for all classes in a list of file paths/patterns
    }

    public static class SkeletonFragment extends VirtualFragment {
        private final List<String> targetIdentifiers; // FQ class names or file paths/patterns
        private final SummaryType summaryType;

        public SkeletonFragment(IContextManager contextManager, List<String> targetIdentifiers, SummaryType summaryType) {
            super(contextManager);
            assert targetIdentifiers != null && !targetIdentifiers.isEmpty();
            assert summaryType != null;
            this.targetIdentifiers = List.copyOf(targetIdentifiers);
            this.summaryType = summaryType;
        }

        public SkeletonFragment(int existingId, IContextManager contextManager, List<String> targetIdentifiers, SummaryType summaryType) {
            super(existingId, contextManager);
            assert targetIdentifiers != null && !targetIdentifiers.isEmpty();
            assert summaryType != null;
            this.targetIdentifiers = List.copyOf(targetIdentifiers);
            this.summaryType = summaryType;
        }

        private Map<CodeUnit, String> fetchSkeletons() {
            IAnalyzer analyzer;
            try {
                analyzer = contextManager.getAnalyzer();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Map.of();
            }

            if (analyzer == null || !analyzer.isCpg()) {
                return Map.of();
            }
            Map<CodeUnit, String> skeletonsMap = new HashMap<>();
            switch (summaryType) {
                case CLASS_SKELETON:
                    for (String className : targetIdentifiers) {
                        analyzer.getDefinition(className).ifPresent(cu -> {
                            if (cu.isClass()) { // Ensure it's a class for getSkeleton
                                analyzer.getSkeleton(cu.fqName()).ifPresent(s -> skeletonsMap.put(cu, s));
                            }
                        });
                    }
                    break;
                case FILE_SKELETONS:
                    // This assumes targetIdentifiers are file paths. Expansion of globs should happen before fragment creation.
                    for (String filePath : targetIdentifiers) {
                        // We need a ProjectFile object. This requires a Project reference or smarter path handling.
                        // For now, this part is problematic without more context (like IProject).
                        // Let's assume filePath is a resolvable path that analyzer can use or lookup.
                        // This is a simplification. WorkspaceTools will construct ProjectFile instances.
                        // Here, we'd ideally have ProjectFile instances in targetIdentifiers if type is FILE_SKELETONS.
                        // For now, this will likely not work as expected for FILE_SKELETONS from this method alone.
                        // The WorkspaceTools will handle creating SkeletonFragments for files correctly.
                        // This fetchSkeletons is more for CLASS_SKELETON type if called directly.
                         analyzer.getFileFor(filePath).ifPresent(pf -> skeletonsMap.putAll(analyzer.getSkeletons(pf)));
                    }
                    break;
            }
            return skeletonsMap;
        }

        @Override
        public String text() {
            Map<CodeUnit, String> skeletons = fetchSkeletons();
            if (skeletons.isEmpty()) {
                return "No summaries found for: " + String.join(", ", targetIdentifiers);
            }

            // Group by package, then format
            var skeletonsByPackage = skeletons.entrySet().stream()
                    .collect(Collectors.groupingBy(
                            e -> e.getKey().packageName().isEmpty() ? "(default package)" : e.getKey().packageName(),
                            Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new)
                    ));

            return skeletonsByPackage.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(pkgEntry -> {
                        String packageHeader = "package " + pkgEntry.getKey() + ";";
                        String pkgCode = pkgEntry.getValue().values().stream().collect(Collectors.joining("\n\n"));
                        return packageHeader + "\n\n" + pkgCode;
                    })
                    .collect(Collectors.joining("\n\n"));
        }
        
        @Override
        public boolean isDynamic() {
            return true;
        }

        @Override
        public Set<CodeUnit> sources() {
            return fetchSkeletons().keySet();
        }

        @Override
        public Set<ProjectFile> files() {
            IAnalyzer analyzer;
            try {
                analyzer = contextManager.getAnalyzer();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Set.of();
            }
            if (analyzer == null || analyzer.isEmpty()) {
                return Set.of();
            }
            return sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
        }

        @Override
        public String description() {
            String typeStr = switch (summaryType) {
                case CLASS_SKELETON -> "Summary";
                case FILE_SKELETONS -> "File Summaries";
            };
            return "%s of %s".formatted(typeStr, targetIdentifiers.stream().collect(Collectors.joining(", ")));
        }


        @Override
        public boolean isEligibleForAutoContext() {
            // If it's an auto-context fragment itself, it shouldn't contribute to seeding a new auto-context.
            // User-added summaries are fine.
            // This needs a way to distinguish. For now, assume all are eligible if user-added.
            // AutoContext itself isn't represented by a SkeletonFragment that users add via tools.
            return summaryType != SummaryType.CLASS_SKELETON; // A heuristic: auto-context typically CLASS_SKELETON of many classes
        }

        @Override
        public String format() throws IOException, InterruptedException {
            return """
                    <summary targets="%s" type="%s" fragmentid="%d">
                    %s
                    </summary>
                    """.stripIndent().formatted(
                    String.join(", ", targetIdentifiers),
                    summaryType.name(),
                    id(),
                    text() // No analyzer
            );
        }

        @Override
        public String formatSummary() {
            try {
                return format();
            } catch (IOException | InterruptedException e) {
                return description(); // fallback
            }
        }

        public List<String> getTargetIdentifiers() { return targetIdentifiers; }
        public SummaryType getSummaryType() { return summaryType; }

        @Override
        public String syntaxStyle() {
            // Skeletons are usually in the language of the summarized code.
            // Default to Java or try to infer from a source CodeUnit if available.
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }

        @Override
        public String toString() {
            return "SkeletonFragment('%s')".formatted(description());
        }
    }

    interface OutputFragment {
        List<TaskEntry> entries();
    }

    /**
     * represents the entire Task History
     */
    public static class HistoryFragment extends VirtualFragment implements OutputFragment {
        private final List<TaskEntry> history; // Content is fixed once created

        public HistoryFragment(IContextManager contextManager, List<TaskEntry> history) {
            super(contextManager);
            assert history != null;
            this.history = List.copyOf(history);
        }

        public HistoryFragment(int existingId, IContextManager contextManager, List<TaskEntry> history) {
            super(existingId, contextManager);
            assert history != null;
            this.history = List.copyOf(history);
        }

        public List<TaskEntry> entries() {
            return history;
        }

        @Override
        public boolean isText() {
            return false;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public String text() {
            // FIXME the right thing to do here is probably to throw UnsupportedOperationException,
            // but lots of stuff breaks without text(), so I am putting that off for another refactor
            return TaskEntry.formatMessages(history.stream().flatMap(e -> e.isCompressed()
                                                                          ? Stream.of(Messages.customSystem(e.summary()))
                                                                          : e.log().messages().stream()).toList());
        }

        @Override
        public Set<CodeUnit> sources() {
            return Set.of();
        }

        @Override
        public Set<ProjectFile> files() {
            return Set.of();
        }

        @Override
        public String description() {
            return "Task History (" + history.size() + " task%s)".formatted(history.size() > 1 ? "s" : "");
        }

        @Override
        public String format() {
            return """
                    <taskhistory fragmentid="%d">
                    %s
                    </taskhistory>
                    """.stripIndent().formatted(id(), text()); // Analyzer not used by its text()
        }

        @Override
        public String formatSummary() {
            return "";
        }

        @Override
        public String toString() {
            return "ConversationFragment(" + history.size() + " tasks)";
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }
    }

    /**
     * represents a single session's Task History
     */
    public static class TaskFragment extends VirtualFragment implements OutputFragment {
        private final EditBlockParser parser; // TODO this doesn't belong in TaskFragment anymore
        private final List<ChatMessage> messages; // Content is fixed once created
        private final String sessionName;

        public TaskFragment(IContextManager contextManager, EditBlockParser parser, List<ChatMessage> messages, String sessionName) {
            super(contextManager);
            this.parser = parser;
            this.messages = messages;
            this.sessionName = sessionName;
        }

        public TaskFragment(IContextManager contextManager, List<ChatMessage> messages, String sessionName) {
            this(contextManager, EditBlockParser.instance, messages, sessionName);
        }

        public TaskFragment(int existingId, IContextManager contextManager, EditBlockParser parser, List<ChatMessage> messages, String sessionName) {
            super(existingId, contextManager);
            this.parser = parser;
            this.messages = messages;
            this.sessionName = sessionName;
        }

        public TaskFragment(int existingId, IContextManager contextManager, List<ChatMessage> messages, String sessionName) {
            this(existingId, contextManager, EditBlockParser.instance, messages, sessionName);
        }

        @Override
        public boolean isText() {
            return false;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public String description() {
            return sessionName;
        }

        @Override
        public String text() {
            // FIXME the right thing to do here is probably to throw UnsupportedOperationException,
            // but lots of stuff breaks without text(), so I am putting that off for another refactor
            return TaskEntry.formatMessages(messages);
        }

        @Override
        public String formatSummary() {
            try {
                return format(); // if it's explicitly added to the workspace it's probably important
            } catch (IOException | InterruptedException e) {
                return description(); // fallback
            }
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }

        public List<ChatMessage> messages() {
            return messages;
        }

        public List<TaskEntry> entries() {
            return List.of(new TaskEntry(-1, this, null));
        }

        public EditBlockParser parser() {
            return parser;
        }
    }
}
