package io.github.jbellis.brokk.context;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.SessionResult;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.context.ContextFragment.HistoryFragment;
import io.github.jbellis.brokk.context.ContextFragment.SkeletonFragment;
import io.github.jbellis.brokk.analyzer.JoernAnalyzer;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.Json;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Encapsulates all state that will be sent to the model (prompts, filename context, conversation history).
 */
public class Context {
    private static final Logger logger = LogManager.getLogger(Context.class);
    private static final AtomicInteger idCounter = new AtomicInteger(0);

    private static int newId() {
        return idCounter.incrementAndGet();
    }

    public static final int MAX_AUTO_CONTEXT_FILES = 100;
    private static final String WELCOME_ACTION = "Welcome to Brokk";
    public static final String SUMMARIZING = "(Summarizing)";

    private final transient IContextManager contextManager;
    final List<ContextFragment.ProjectPathFragment> editableFiles;
    final List<ContextFragment.PathFragment> readonlyFiles;
    final List<ContextFragment.VirtualFragment> virtualFragments;

    /** Task history list. Each entry represents a user request and the subsequent conversation */
    final List<TaskEntry> taskHistory;

    /** backup of original contents for /undo, does not carry forward to Context children */
    transient final Map<ProjectFile, String> originalContents;

    /** LLM output or other parsed content, with optional fragment. May be null */
    transient final ContextFragment.TaskFragment parsedOutput;

    /** description of the action that created this context, can be a future (like PasteFragment) */
    transient final Future<String> action;

    /**
     * Unique transient identifier for this context instance.
     * Used to track identity across asynchronous autocontext refresh
     */
    transient final int id;

    /**
     * Constructor for initial empty context
     */
    public Context(@NotNull IContextManager contextManager, String initialOutputText) {
        this(newId(),
             Objects.requireNonNull(contextManager, "contextManager cannot be null"),
             List.of(),
             List.of(),
             List.of(),
             new ArrayList<>(),
             Map.of(),
             getWelcomeOutput(contextManager, initialOutputText), // Pass contextManager here
             CompletableFuture.completedFuture(WELCOME_ACTION));
    }

    private static @NotNull ContextFragment.TaskFragment getWelcomeOutput(IContextManager contextManager, String initialOutputText) {
        var messages = List.<ChatMessage>of(Messages.customSystem(initialOutputText));
        return new ContextFragment.TaskFragment(contextManager, messages, "Welcome");
    }

    /**
     * Constructor for initial empty context with empty output. Tests only
     */
    Context(@NotNull IContextManager contextManager) { // Made package-private and kept @NotNull
        this(Objects.requireNonNull(contextManager, "contextManager cannot be null"), "placeholder");
    }

    Context(int id,
            @NotNull IContextManager contextManager,
            List<ContextFragment.ProjectPathFragment> editableFiles,
            List<ContextFragment.PathFragment> readonlyFiles,
            List<ContextFragment.VirtualFragment> virtualFragments,
            List<TaskEntry> taskHistory,
            Map<ProjectFile, String> originalContents,
            ContextFragment.TaskFragment parsedOutput,
            Future<String> action)
    {
        assert id > 0;
        // contextManager is asserted non-null by the caller or public constructor
        assert editableFiles != null;
        assert readonlyFiles != null;
        assert virtualFragments != null;
        assert taskHistory != null;
        assert originalContents != null;
        assert action != null;
        this.id = id;
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null in private constructor");
        this.editableFiles = List.copyOf(editableFiles);
        this.readonlyFiles = List.copyOf(readonlyFiles);
        this.virtualFragments = List.copyOf(virtualFragments);
        this.taskHistory = List.copyOf(taskHistory); // Ensure immutability
        this.originalContents = originalContents;
        this.parsedOutput = parsedOutput;
        this.action = action;
    }

    /**
     * Creates a new Context with an additional set of editable files. Rebuilds autoContext if toggled on.
     */
    public Context addEditableFiles(Collection<ContextFragment.ProjectPathFragment> paths) { // IContextManager is already member
        var toAdd = paths.stream().filter(fragment -> !editableFiles.contains(fragment)).toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        var newEditable = new ArrayList<>(editableFiles);
        newEditable.addAll(toAdd);

        String actionDetails = toAdd.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Edit " + actionDetails;
        return getWithFragments(newEditable, readonlyFiles, virtualFragments, action);
    }

    public Context addReadonlyFiles(Collection<ContextFragment.PathFragment> paths) { // IContextManager is already member
        var toAdd = paths.stream().filter(fragment -> !readonlyFiles.contains(fragment)).toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.addAll(toAdd);

        String actionDetails = toAdd.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Read " + actionDetails;
        return getWithFragments(editableFiles, newReadOnly, virtualFragments, action);
    }

    public Context removeEditableFiles(List<ContextFragment.PathFragment> fragments) { // IContextManager is already member
        var newEditable = new ArrayList<>(editableFiles);
        newEditable.removeAll(fragments);
        if (newEditable.equals(editableFiles)) {
            return this;
        }

        String actionDetails = fragments.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Removed " + actionDetails;
        return getWithFragments(newEditable, readonlyFiles, virtualFragments, action);
    }

    public Context removeReadonlyFiles(List<? extends ContextFragment.PathFragment> fragments) { // IContextManager is already member
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.removeAll(fragments);
        if (newReadOnly.equals(readonlyFiles)) {
            return this;
        }

        String actionDetails = fragments.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Removed " + actionDetails;
        return getWithFragments(editableFiles, newReadOnly, virtualFragments, action);
    }

    public Context removeVirtualFragments(List<? extends ContextFragment.VirtualFragment> fragments) { // IContextManager is already member
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.removeAll(fragments);
        if (newFragments.equals(virtualFragments)) {
            return this;
        }

        String actionDetails = fragments.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Removed " + actionDetails;
        return getWithFragments(editableFiles, readonlyFiles, newFragments, action);
    }

    public Context addVirtualFragment(ContextFragment.VirtualFragment fragment) { // IContextManager is already member
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.add(fragment);

        String action = "Added " + fragment.shortDescription();
        return getWithFragments(editableFiles, readonlyFiles, newFragments, action);
    }

    /**
     * Adds a virtual fragment and uses the same future for both fragment description and action
     */
    public Context addPasteFragment(ContextFragment.PasteTextFragment fragment, Future<String> summaryFuture) { // IContextManager is already member
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.add(fragment);

        // Create a future that prepends "Added " to the summary
        Future<String> actionFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return "Added paste of " + summaryFuture.get();
            } catch (Exception e) {
                return "Added paste";
            }
        });

        return withFragments(editableFiles, readonlyFiles, newFragments, actionFuture);
    }

    public Context removeBadFragment(ContextFragment f) { // IContextManager is already member
        if (f.getType().isPathFragment()) {
            var pf = (ContextFragment.PathFragment) f;
            var inEditable = editableFiles.contains(pf);
            var inReadonly = readonlyFiles.contains(pf);

            if (inEditable) {
                var newEditable = new ArrayList<>(editableFiles);
                newEditable.remove(pf);
                return getWithFragments(newEditable, readonlyFiles, virtualFragments,
                                        "Removed unreadable " + pf.description());
            } else if (inReadonly) {
                var newReadonly = new ArrayList<>(readonlyFiles);
                newReadonly.remove(pf);
                return getWithFragments(editableFiles, newReadonly, virtualFragments,
                                        "Removed unreadable " + pf.description());
            }
            return this;
        } else if (f.getType().isVirtualFragment()) {
            var vf = (ContextFragment.VirtualFragment) f;
            var newFragments = new ArrayList<>(virtualFragments);
            if (newFragments.remove(vf)) {
                return getWithFragments(editableFiles, readonlyFiles, newFragments,
                                        "Removed unreadable " + vf.description());
            }
            return this;
        } else {
            // This case should ideally not be reached if all fragments correctly report their type.
            // However, as a fallback or for future fragment types not yet covered by isPath/isVirtual,
            // log a warning and attempt a generic removal if possible, or return 'this'.
            logger.warn("Unknown fragment type encountered in removeBadFragment: {}", f.getClass().getName());
            // Attempt removal based on object equality if not a known type, though this might not be effective
            // if the fragment isn't in any of the primary lists or if equality isn't well-defined.
            // For now, returning 'this' to avoid unexpected behavior.
            return this;
        }
    }

    @NotNull
    private Context getWithFragments(List<ContextFragment.ProjectPathFragment> newEditableFiles,
                                     List<ContextFragment.PathFragment> newReadonlyFiles,
                                     List<ContextFragment.VirtualFragment> newVirtualFragments,
                                     String action) {
        return withFragments(newEditableFiles, newReadonlyFiles, newVirtualFragments, CompletableFuture.completedFuture(action));
    }

    /**
     * 1) Gather all classes from each fragment.
     * 2) Compute PageRank with those classes as seeds, requesting up to 2*MAX_AUTO_CONTEXT_FILES
     * 3) Return a SkeletonFragment constructed with the FQNs of the top results.
     */
    public SkeletonFragment buildAutoContext(int topK) throws InterruptedException {
        IAnalyzer analyzer;
        analyzer = contextManager.getAnalyzer();

        // Collect ineligible classnames from fragments not eligible for auto-context
        var ineligibleSources = Streams.concat(editableFiles.stream(), readonlyFiles.stream(), virtualFragments.stream())
                .filter(f -> !f.isEligibleForAutoContext())
                .flatMap(f -> f.sources().stream()) // No analyzer
                .collect(Collectors.toSet());

        // Collect initial seeds
        var weightedSeeds = new HashMap<String, Double>();
        // editable files have a weight of 1.0, each
        editableFiles.stream().flatMap(f -> f.sources().stream()).forEach(unit -> { // No analyzer
            weightedSeeds.put(unit.fqName(), 1.0);
        });
        // everything else splits a weight of 1.0
        Streams.concat(readonlyFiles.stream(), virtualFragments.stream())
                .flatMap(f -> f.sources().stream()) // No analyzer
                .forEach(unit ->
                         {
                             weightedSeeds.merge(unit.fqName(), 1.0 / (readonlyFiles.size() + virtualFragments.size()), Double::sum);
                         });

        // If no seeds, we can't compute pagerank
        if (weightedSeeds.isEmpty()) {
            // Pass contextManager to SkeletonFragment constructor
            return new SkeletonFragment(contextManager, List.of(), ContextFragment.SummaryType.CLASS_SKELETON); // Empty skeleton fragment
        }

        return buildAutoContextFragment(contextManager, analyzer, weightedSeeds, ineligibleSources, topK);
    }

    public static SkeletonFragment buildAutoContextFragment(IContextManager contextManager, IAnalyzer analyzer, Map<String, Double> weightedSeeds, Set<CodeUnit> ineligibleSources, int topK) {
        var pagerankResults = AnalyzerUtil.combinedPagerankFor(analyzer, weightedSeeds);

        List<String> targetFqns = new ArrayList<>();
        for (var codeUnit : pagerankResults) {
            var fqcn = codeUnit.fqName();
            var sourceFileOption = analyzer.getFileFor(fqcn);
            if (sourceFileOption.isEmpty()) {
                logger.warn("No source file found for class {}", fqcn);
                continue;
            }
            var sourceFile = sourceFileOption.get();
            // Check if the class or its parent is in ineligible classnames
            boolean eligible = !(ineligibleSources.contains(codeUnit));
            if (fqcn.contains("$")) {
                var parentFqcn = fqcn.substring(0, fqcn.indexOf('$'));
                // FIXME generalize this
                // Check if the analyzer supports cuClass and cast if necessary
                if (analyzer instanceof JoernAnalyzer aa) {
                    // Use the analyzer helper method which handles splitting correctly
                    var parentUnitOpt = aa.cuClass(parentFqcn, sourceFile); // Returns scala.Option
                    if (parentUnitOpt.isDefined() && ineligibleSources.contains(parentUnitOpt.get())) {
                        eligible = false;
                    }
                } else {
                    logger.warn("Analyzer of type {} does not support direct CodeUnit creation, skipping parent eligibility check for {}",
                                analyzer.getClass().getSimpleName(), fqcn);
                }
            }

            if (eligible) {
                // Check if skeleton exists before adding, to ensure it's a valid target for summary
                if (analyzer.getSkeleton(fqcn).isPresent()) {
                    targetFqns.add(fqcn);
                }
            }
            if (targetFqns.size() >= topK) {
                break;
            }
        }
        if (targetFqns.isEmpty()) {
            // Pass contextManager to SkeletonFragment constructor
            return new SkeletonFragment(contextManager, List.of(), ContextFragment.SummaryType.CLASS_SKELETON); // Empty
        }
        // Pass contextManager to SkeletonFragment constructor
        return new SkeletonFragment(contextManager, targetFqns, ContextFragment.SummaryType.CLASS_SKELETON);
    }

    // ---------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------

    public Stream<ContextFragment.ProjectPathFragment> editableFiles() {
        return editableFiles.stream();
    }

    public Stream<ContextFragment.PathFragment> readonlyFiles() {
        return readonlyFiles.stream();
    }

    public Stream<ContextFragment.VirtualFragment> virtualFragments() {
        return virtualFragments.stream();
    }

    /**
     * Returns readonly files and virtual fragments (excluding usage fragments) as a combined stream
     */
    public Stream<ContextFragment> getReadOnlyFragments() {
        return Streams.concat(
            readonlyFiles.stream(),
            virtualFragments.stream().filter(f -> f.getType() != ContextFragment.FragmentType.USAGE)
        );
    }

    /**
     * Returns editable files and usage fragments as a combined stream
     */
    public Stream<ContextFragment> getEditableFragments() {
        // Helper record for associating a fragment with its mtime for safe sorting and filtering
        record EditableFileWithMtime(ContextFragment.ProjectPathFragment fragment, long mtime) {}

        Stream<ContextFragment.ProjectPathFragment> sortedEditableFiles =
            editableFiles.stream()
                .map(ef -> {
                    try {
                        return new EditableFileWithMtime(ef, ef.file().mtime());
                    } catch (IOException e) {
                        logger.warn("Could not get mtime for editable file [{}], it will be excluded from ordered editable fragments.",
                                    ef.shortDescription(), e);
                        return new EditableFileWithMtime(ef, -1L); // Mark for filtering
                    }
                })
                .filter(mf -> mf.mtime() >= 0) // Filter out files with errors or negative mtime
                .sorted(Comparator.comparingLong(EditableFileWithMtime::mtime)) // Sort by mtime
                .map(EditableFileWithMtime::fragment); // Extract the original fragment

        return Streams.concat(virtualFragments.stream().filter(f -> f.getType() == ContextFragment.FragmentType.USAGE),
                              sortedEditableFiles);
    }

    public Stream<? extends ContextFragment> allFragments() {
        return Streams.concat(editableFiles.stream(),
                              readonlyFiles.stream(),
                              virtualFragments.stream());
    }

    /**
     * Creates a new context with custom collections and action description,
     * refreshing auto-context if needed.
     */
    private Context withFragments(List<ContextFragment.ProjectPathFragment> newEditableFiles,
                                  List<ContextFragment.PathFragment> newReadonlyFiles,
                                  List<ContextFragment.VirtualFragment> newVirtualFragments,
                                  Future<String> action) {
        return new Context(
                newId(),
                contextManager,
                newEditableFiles,
                newReadonlyFiles,
                newVirtualFragments,
                taskHistory,
                Map.of(),
                null,
                action
        );
    }

    public Context removeAll() {
        String action = "Dropped all context";
        // editable
        // readonly
        // virtual
        // task history
        // original contents
        // parsed output
        return new Context(newId(),
                           contextManager,
                           List.of(), // editable
                           List.of(), // readonly
                           List.of(), // virtual
                           List.of(), // task history
                           Map.of(), // original contents
                           null, // parsed output
                           CompletableFuture.completedFuture(action));
    }

    // Method removed in favor of toFragment(int position)

    public boolean isEmpty() {
        return editableFiles.isEmpty()
                && readonlyFiles.isEmpty()
                && virtualFragments.isEmpty()
                && taskHistory.isEmpty();
    }

    /**
     * Creates a new TaskEntry with the correct sequence number based on the current history.
     * @return A new TaskEntry.
     */
    public TaskEntry createTaskEntry(SessionResult result) {
        int nextSequence = taskHistory.isEmpty() ? 1 : taskHistory.getLast().sequence() + 1;
        return TaskEntry.fromSession(nextSequence, result);
    }

    /**
     * Adds a new TaskEntry to the history.
     *
     * @param taskEntry        The pre-constructed TaskEntry to add.
     * @param originalContents Map of original file contents for undo purposes.
     * @param parsed           The parsed output associated with this task.
     * @param action           A future describing the action that created this history entry.
     * @return A new Context instance with the added task history.
     */
    public Context addHistoryEntry(TaskEntry taskEntry, ContextFragment.TaskFragment parsed, Future<String> action, Map<ProjectFile, String> originalContents) {
        var newTaskHistory = Streams.concat(taskHistory.stream(), Stream.of(taskEntry)).toList();
        // new task history list
        return new Context(newId(),
                           contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           newTaskHistory, // new task history list
                           originalContents,
                           parsed,
                           action);
    }


    public Context clearHistory() {
        return new Context(newId(),
                           contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           List.of(), // Cleared task history
                           Map.of(),
                           null,
                           CompletableFuture.completedFuture("Cleared task history"));
    }

    public Context withOriginalContents(Map<ProjectFile, String> fileContents) {
        // This context is temporary/internal for undo, does not represent a new user action,
        // so it retains the same ID and does not call refresh.
        return new Context(this.id,
                           contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           taskHistory, // Use task history here
                           fileContents,
                           this.parsedOutput,
                           this.action);
    }

    /**
     * @return an immutable copy of the task history.
     */
    public List<TaskEntry> getTaskHistory() {
        return taskHistory;
    }

    /**
     * Get the action that created this context
     */
    public String getAction() {
        if (action.isDone()) {
            try {
                return action.get();
            } catch (Exception e) {
                logger.warn("Error retrieving action", e);
                return "(Error retrieving action)";
            }
        }
        return SUMMARIZING;
    }

    /**
     * Get the unique transient identifier for this context instance.
     */
    public int getId() {
        return id;
    }

    public IContextManager getContextManager() {
        return contextManager;
    }

    /**
     * Returns all fragments in display order:
     * 0 => conversation history (if not empty)
     * 1 => autoContext (always present, even when DISABLED)
     * next => read-only (readonlyFiles + virtualFragments)
     * finally => editable
     */
    public List<ContextFragment> getAllFragmentsInDisplayOrder() {
        var result = new ArrayList<ContextFragment>();

        // Then conversation history
        if (!taskHistory.isEmpty()) {
            result.add(new HistoryFragment(contextManager, taskHistory));
        }

        // then read-only
        result.addAll(readonlyFiles);
        result.addAll(virtualFragments);

        // then editable
        result.addAll(editableFiles);

        return result;
    }

    public Context withParsedOutput(ContextFragment.TaskFragment parsedOutput, Future<String> action) {
        return new Context(newId(),
                           contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           taskHistory,
                           originalContents,
                           parsedOutput,
                           action);
    }

    /**
     * Creates a new Context with a modified task history list.
     * This generates a new context state with a new ID and action.
     *
     * @param newHistory The new list of TaskEntry objects.
     * @return A new Context instance with the updated history.
     */
    public Context withCompressedHistory(List<TaskEntry> newHistory) {
        return new Context(newId(),
                           contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           newHistory, // Use the new history
                           Map.of(), // original contents
                           null,     // parsed output
                           CompletableFuture.completedFuture("Compressed History"));
    }

    public ContextFragment.TaskFragment getParsedOutput() {
        return parsedOutput;
    }

    /**
     * Serializes this Context to JSON using the DTO layer.
     */
    public String toJson() {
        return Json.toJson(DtoMapper.toDto(this));
    }

    /**
     * Deserializes a Context from JSON using the DTO layer.
     */
    public static Context fromJson(String json, IContextManager mgr) {
        var dto = Json.fromJson(json, ContextDto.class);
        return DtoMapper.fromDto(dto, mgr);
    }

    /**
     * Creates a new Context with the specified context manager.
     * Used to initialize the context manager reference after deserialization.
     * This does not represent a new state, so it retains the ID.
     */
    public Context withContextManager(IContextManager contextManager) {
        return new Context(
                this.id, // Retain ID from deserialized object
                contextManager,
                editableFiles,
                readonlyFiles,
                virtualFragments,
                taskHistory,
                originalContents,
                parsedOutput,
                action
        );
    }

    /**
     * Creates a new Context that copies specific elements from the provided context.
     * This creates a reset point by:
     * - Using the files and fragments from the source context
     * - Keeping the history messages from the current context
     * - Setting up properly for rebuilding autoContext
     * - Clearing parsed output and original contents
     * - Setting a suitable action description
     */
    public static Context createFrom(Context sourceContext, Context currentContext) {
        assert sourceContext != null;
        assert currentContext != null;

        // Unfreeze fragments from the source context if they are frozen
        var unfrozenEditableFiles = sourceContext.editableFiles.stream()
                .map(fragment -> unfreezeFragmentIfNeeded(fragment, currentContext.contextManager))
                .collect(Collectors.toList());
        
        var unfrozenReadonlyFiles = sourceContext.readonlyFiles.stream()
                .map(fragment -> unfreezeFragmentIfNeeded(fragment, currentContext.contextManager))
                .collect(Collectors.toList());
        
        var unfrozenVirtualFragments = sourceContext.virtualFragments.stream()
                .map(fragment -> unfreezeFragmentIfNeeded(fragment, currentContext.contextManager))
                .collect(Collectors.toList());

        // New ID for the reset point
        return new Context(newId(), // New ID for the reset point
                           currentContext.contextManager,
                           unfrozenEditableFiles,
                           unfrozenReadonlyFiles,
                           unfrozenVirtualFragments,
                           currentContext.taskHistory,
                           Map.of(),
                           null,
                           CompletableFuture.completedFuture("Reset context to historical state"));
    }

    /**
     * Creates a new Context that copies specific elements, including task history, from the provided source context.
     * This creates a reset point by:
     * - Using the files and fragments from the source context
     * - Using the history messages from the source context
     * - Setting up properly for rebuilding autoContext
     * - Clearing parsed output and original contents
     * - Setting a suitable action description
     */
    public static Context createFromIncludingHistory(Context sourceContext, Context currentContext) {
        assert sourceContext != null;
        assert currentContext != null;

        // Unfreeze fragments from the source context if they are frozen
        var unfrozenEditableFiles = sourceContext.editableFiles.stream()
                .map(fragment -> unfreezeFragmentIfNeeded(fragment, currentContext.contextManager))
                .collect(Collectors.toList());
        
        var unfrozenReadonlyFiles = sourceContext.readonlyFiles.stream()
                .map(fragment -> unfreezeFragmentIfNeeded(fragment, currentContext.contextManager))
                .collect(Collectors.toList());
        
        var unfrozenVirtualFragments = sourceContext.virtualFragments.stream()
                .map(fragment -> unfreezeFragmentIfNeeded(fragment, currentContext.contextManager))
                .collect(Collectors.toList());

        // New ID for the reset point
        return new Context(newId(), // New ID for the reset point
                           currentContext.contextManager,
                           unfrozenEditableFiles,
                           unfrozenReadonlyFiles,
                           unfrozenVirtualFragments,
                           sourceContext.taskHistory, // Use task history from sourceContext
                           Map.of(),
                           null,
                           CompletableFuture.completedFuture("Reset context and history to historical state"));
    }

    /**
     * Calculates the maximum ID from all fragments and task history in this context.
     * Used to ensure proper ID sequencing when deserializing contexts.
     */
    public int getMaxId() {
        var maxId = 0;

        // Check editable files
        maxId = Math.max(maxId, editableFiles.stream()
                .mapToInt(f -> f.id())
                .max()
                .orElse(0));

        // Check readonly files
        maxId = Math.max(maxId, readonlyFiles.stream()
                .mapToInt(f -> f.id())
                .max()
                .orElse(0));

        // Check virtual fragments
        maxId = Math.max(maxId, virtualFragments.stream()
                .mapToInt(f -> f.id())
                .max()
                .orElse(0));

        // Check task history
        maxId = Math.max(maxId, taskHistory.stream()
                .filter(t -> t.log() != null)
                .mapToInt(t -> t.log().id())
                .max()
                .orElse(0));

        return maxId;
    }

    /**
     * Creates a new Context with dynamic fragments replaced by their frozen counterparts.
     * This method is used by ContextHistory to ensure that contexts stored in history
     * contain point-in-time snapshots that don't depend on the filesystem or analyzer.
     *
     * @return A new Context instance with dynamic fragments frozen
     */
    Context freeze() {
        try {
            // Process editable files
            var frozenEditableFiles = new ArrayList<ContextFragment.ProjectPathFragment>();
            for (var fragment : editableFiles) {
                if (fragment.isDynamic()) {
                    var frozen = FrozenFragment.freeze(fragment, contextManager);
                    // FrozenFragment extends VirtualFragment, so we can't add it to editableFiles
                    // Instead, we need to handle this differently - frozen editable files become virtual fragments
                } else {
                    frozenEditableFiles.add(fragment);
                }
            }

            // Process readonly files
            var frozenReadonlyFiles = new ArrayList<ContextFragment.PathFragment>();
            for (var fragment : readonlyFiles) {
                if (fragment.isDynamic()) {
                    var frozen = FrozenFragment.freeze(fragment, contextManager);
                    // Add frozen fragment as virtual fragment instead
                } else {
                    frozenReadonlyFiles.add(fragment);
                }
            }

            // Process virtual fragments
            var frozenVirtualFragments = new ArrayList<ContextFragment.VirtualFragment>();
            for (var fragment : virtualFragments) {
                if (fragment.isDynamic()) {
                    var frozen = FrozenFragment.freeze(fragment, contextManager);
                    frozenVirtualFragments.add(frozen);
                } else {
                    frozenVirtualFragments.add(fragment);
                }
            }

            // Add frozen path fragments to virtual fragments since FrozenFragment extends VirtualFragment
            for (var fragment : editableFiles) {
                if (fragment.isDynamic()) {
                    var frozen = FrozenFragment.freeze(fragment, contextManager);
                    frozenVirtualFragments.add(frozen);
                }
            }

            for (var fragment : readonlyFiles) {
                if (fragment.isDynamic()) {
                    var frozen = FrozenFragment.freeze(fragment, contextManager);
                    frozenVirtualFragments.add(frozen);
                }
            }

            return new Context(
                    this.id, // Keep same ID as this is the same logical context
                    this.contextManager,
                    frozenEditableFiles, // Only non-dynamic editable files remain
                    frozenReadonlyFiles, // Only non-dynamic readonly files remain
                    frozenVirtualFragments, // All virtual fragments plus frozen path fragments
                    this.taskHistory,
                    Map.of(), // Clear original contents as frozen fragments don't need undo
                    this.parsedOutput,
                    this.action
            );
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to freeze dynamic fragments, returning original context", e);
            return this;
        }
    }

    /**
     * Helper method to unfreeze a fragment if it's a FrozenFragment, otherwise return as-is.
     * Used when restoring contexts from history to get live fragments.
     */
    @SuppressWarnings("unchecked")
    private static <T extends ContextFragment> T unfreezeFragmentIfNeeded(T fragment, IContextManager contextManager) {
        if (fragment instanceof FrozenFragment frozen) {
            try {
                return (T) frozen.unfreeze(contextManager);
            } catch (IOException e) {
                logger.warn("Failed to unfreeze fragment {}: {}", frozen.description(), e.getMessage());
                // Return the frozen fragment if unfreezing fails
                return fragment;
            }
        }
        return fragment;
    }
}
