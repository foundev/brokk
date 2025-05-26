package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class ContextSerializationTest {
    @TempDir
    Path tempDir;
    private IContextManager mockContextManager;

    @BeforeEach
    void setup() {
        // Setup mock context manager
        mockContextManager = new IContextManager() {
        };
    }

    @Test
    void testBasicContextSerialization() throws Exception {
        // Create a context with minimal state
        Context context = new Context(mockContextManager);

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify non-transient fields were preserved
        assertEquals(context.editableFiles.size(), deserialized.editableFiles.size());
        assertEquals(context.readonlyFiles.size(), deserialized.readonlyFiles.size());
        assertEquals(context.virtualFragments.size(), deserialized.virtualFragments.size());

        // Most transient fields should be initialized to empty
        assertNull(deserialized.contextManager);
        assertNotNull(deserialized.parsedOutput); // Welcome SessionFragment
        assertNotNull(deserialized.originalContents); // Empty Map
        assertNotNull(deserialized.taskHistory); // Empty List (changed from historyMessages)

        // We injected output via SessionFragment, check its formatted text
        var expectedOutputText = """
                <message type=custom>
                  stub
                </message>""".stripIndent();
        assertEquals(expectedOutputText, deserialized.parsedOutput.text().strip());
    }

    @Test
    void testContextWithFragmentsSerialization() throws Exception {
        // Create test files
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot);

        var projectFile = new ProjectFile(repoRoot, "src/main/java/Test.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class Test {}");

        var externalFile = new ExternalFile(tempDir.resolve("external.txt").toAbsolutePath());
        Files.writeString(externalFile.absPath(), "This is external content");

        // Create context with fragments
        Context context = new Context(mockContextManager)
                .addEditableFiles(List.of(new ContextFragment.ProjectPathFragment(projectFile)))
                .addReadonlyFiles(List.of(new ContextFragment.ExternalPathFragment(externalFile)))
                .addVirtualFragment(new ContextFragment.StringFragment("virtual content", "Virtual Fragment", SyntaxConstants.SYNTAX_STYLE_NONE));

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify fragment counts
        assertEquals(1, deserialized.editableFiles.size());
        assertEquals(1, deserialized.readonlyFiles.size());
        assertEquals(1, deserialized.virtualFragments.size());

        // Check paths were properly serialized
        ContextFragment.ProjectPathFragment repoFragment = deserialized.editableFiles.get(0);
        assertEquals(projectFile.toString(), repoFragment.file().toString());
        assertEquals(repoRoot.toString(), repoFragment.file().absPath().getParent().getParent().getParent().getParent().toString());

        ContextFragment.PathFragment externalFragment = deserialized.readonlyFiles.get(0);
        assertEquals(externalFile.toString(), externalFragment.file().toString());

        // Verify the ExternalPathFragment can be read correctly after deserialization
        assertDoesNotThrow(() -> {
            String content = externalFragment.text();
            assertEquals("This is external content", content);
            String formatted = externalFragment.format();
            assertTrue(formatted.contains("external.txt"));
            assertTrue(formatted.contains("This is external content"));
        });
    }

    @Test
    void testAllVirtualFragmentTypes() throws Exception {
        Context context = new Context(mockContextManager);

        // Create mock RepoFile for CodeUnit construction
        ProjectFile mockFile = new ProjectFile(tempDir, "Mock.java");

        // Add examples of each *serializable* VirtualFragment type
        context = context
                .addVirtualFragment(new ContextFragment.StringFragment("string content", "String Fragment", SyntaxConstants.SYNTAX_STYLE_NONE))
                .addVirtualFragment(new ContextFragment.SkeletonFragment(
                        Map.of(CodeUnit.cls(mockFile, "com.test", "Test"), "class Test {}")
                ));

        // Create SearchFragment separately to ensure sources are serializable
        var searchSources = Set.of(CodeUnit.cls(mockFile, "", "Test"));
        context = context.addVirtualFragment(new ContextFragment.SearchFragment("query", "explanation", searchSources));

        // Add fragments that use Future
        CompletableFuture<String> descFuture = CompletableFuture.completedFuture("description");
        context = context.addPasteFragment(
                new ContextFragment.PasteTextFragment("paste content", descFuture),
                descFuture
        );

        // Add fragment with usage
        context = context.addVirtualFragment(
                new ContextFragment.UsageFragment("Test.method", Set.of(CodeUnit.cls(mockFile, "com.test", "Test")), "Test.method()")
        );

        // Add stacktrace fragment
        context = context.addVirtualFragment(
                new ContextFragment.StacktraceFragment(
                        Set.of(CodeUnit.cls(mockFile, "com.test", "Test")),
                        "original",
                        "NPE",
                        "code"
                )
        );

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify all serializable fragments were preserved (String, Skeleton, Search, Paste, Usage, Stacktrace)
        assertEquals(6, deserialized.virtualFragments.size());

        // Verify fragment types
        var fragmentTypes = deserialized.virtualFragments.stream()
                .map(Object::getClass)
                .toList();

        assertTrue(fragmentTypes.contains(ContextFragment.StringFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.SearchFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.SkeletonFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.PasteTextFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.UsageFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.StacktraceFragment.class));

        // Verify text content for a fragment
        var stringFragment = deserialized.virtualFragments.stream()
                .filter(f -> f instanceof ContextFragment.StringFragment)
                .findFirst()
                .orElseThrow();
        assertEquals("string content", stringFragment.text());

        // Verify SearchFragment content
        var searchFragment = (ContextFragment.SearchFragment) deserialized.virtualFragments.stream()
                .filter(f -> f instanceof ContextFragment.SearchFragment)
                .findFirst()
                .orElseThrow();
        assertEquals("Search: query", searchFragment.description());
        assertTrue(searchFragment.text().contains("query"));
        assertTrue(searchFragment.text().contains("explanation"));
        assertEquals(Set.of(CodeUnit.cls(mockFile, "", "Test")), searchFragment.sources(null)); // project is unused in this impl
    }

    @Test
    void testExternalPathFragmentSerialization() throws Exception {
        // Create an external file
        Path externalPath = tempDir.resolve("serialization-test.txt").toAbsolutePath();
        String testContent = "External file content for serialization test";
        Files.writeString(externalPath, testContent);

        ExternalFile externalFile = new ExternalFile(externalPath);
        ContextFragment.ExternalPathFragment fragment = new ContextFragment.ExternalPathFragment(externalFile);

        // Add to context
        Context context = new Context(mockContextManager)
                .addReadonlyFiles(List.of(fragment));

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify the fragment was properly deserialized
        assertEquals(1, deserialized.readonlyFiles.size());
        ContextFragment.PathFragment deserializedFragment = deserialized.readonlyFiles.get(0);

        // Check if it's the correct type
        assertInstanceOf(ContextFragment.ExternalPathFragment.class, deserializedFragment);

        // Verify path and content
        assertEquals(externalPath.toString(), deserializedFragment.file().toString());

        // Should be able to read the file
        assertEquals(testContent, deserializedFragment.text());

        // Check format method works
        String formatted = deserializedFragment.format();
        assertTrue(formatted.contains("serialization-test.txt"));
        assertTrue(formatted.contains(testContent));
    }

    @Test
    void testRoundTripOfLargeContext() throws Exception {
        // Create test files
        Path repoRoot = tempDir.resolve("largeRepo");
        Files.createDirectories(repoRoot);

        // Create many files
        List<ContextFragment.ProjectPathFragment> editableFiles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ProjectFile file = new ProjectFile(repoRoot, "src/main/java/Test" + i + ".java");
            Files.createDirectories(file.absPath().getParent());
            Files.writeString(file.absPath(), "public class Test" + i + " {}");
            editableFiles.add(new ContextFragment.ProjectPathFragment(file));
        }

        // Create many virtual fragments
        List<ContextFragment.VirtualFragment> virtualFragments = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            virtualFragments.add(new ContextFragment.StringFragment("content " + i, "Fragment " + i, SyntaxConstants.SYNTAX_STYLE_NONE));
        }

        Context context = new Context(mockContextManager);

        // Add all fragments
        context = context.addEditableFiles(editableFiles);
        for (var fragment : virtualFragments) {
            context = context.addVirtualFragment(fragment);
        }

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify counts
        assertEquals(20, deserialized.editableFiles.size());
        assertEquals(15, deserialized.virtualFragments.size());

        // Check content of fragments
        for (int i = 0; i < 15; i++) {
            assertTrue(deserialized.virtualFragments.get(i).text().contains("content"));
            assertTrue(deserialized.virtualFragments.get(i).description().contains("Fragment"));
        }
    }

    @Test
    void testTaskHistorySerialization() throws Exception {
        // Create context
        Context context = new Context(mockContextManager);

        // Create sample chat messages for a full session (User + AI)
        List<ChatMessage> sessionMessages = List.of(
                dev.langchain4j.data.message.UserMessage.from("What is the capital of France?"),
                dev.langchain4j.data.message.AiMessage.from("The capital of France is Paris.")
        );

        // Add history using fromSession, which extracts the first UserMessage as description
        // Provide dummy original contents and parsed output as they are not the focus here
        var originalContents = Map.<ProjectFile, String>of();
        var parsedOutput = new ContextFragment.TaskFragment(sessionMessages, "Test Task");
        Future<String> action = CompletableFuture.completedFuture("Test Task");
        var result = new SessionResult("What is the capital of France?", parsedOutput, Map.of(), new SessionResult.StopDetails(SessionResult.StopReason.SUCCESS));
        var taskEntry = context.createTaskEntry(result);
        context = context.addHistoryEntry(taskEntry, parsedOutput, action, originalContents);

        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");

        // Verify task history count
        assertEquals(1, deserialized.getTaskHistory().size(), "Deserialized context should have one task history entry.");
        TaskEntry deserializedTask = deserialized.getTaskHistory().getFirst();

        // Verify TaskMessages content (log field contains the messages)
        assertNotNull(deserializedTask.log(), "Task log should not be null after deserialization.");
        // Compare the messages list within the TaskFragment
        assertEquals(sessionMessages, deserializedTask.log().messages(), "Task log messages should contain the original session messages.");
        // Check the content of the first message specifically if needed
        assertTrue(deserializedTask.log().messages().getFirst() instanceof dev.langchain4j.data.message.UserMessage, "First message should be a UserMessage.");
        assertEquals("What is the capital of France?", ((dev.langchain4j.data.message.UserMessage) deserializedTask.log().messages().getFirst()).singleText(), "First message content should match.");
            assertNull(deserializedTask.summary(), "Task should not be summarized (summary field should be null).");
            assertInstanceOf(ContextFragment.TaskFragment.class, deserializedTask.log());
        }

    @Test
    void testContextJsonSerialization() throws Exception {
        // Create a context with minimal state
        Context context = new Context(mockContextManager);

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json, "JSON serialization should not return null");
        assertTrue(json.contains("editableFiles"), "JSON should contain editableFiles field");
        assertTrue(json.contains("version"), "JSON should contain version field");

        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify non-transient fields were preserved
        assertEquals(context.editableFiles.size(), deserialized.editableFiles.size());
        assertEquals(context.readonlyFiles.size(), deserialized.readonlyFiles.size());
        assertEquals(context.virtualFragments.size(), deserialized.virtualFragments.size());
        assertEquals(context.taskHistory.size(), deserialized.taskHistory.size());

        // Transient fields should be initialized appropriately
        assertNotNull(deserialized.contextManager);
        assertEquals(mockContextManager, deserialized.contextManager);
    }

    @Test
    void testContextWithFragmentsJsonSerialization() throws Exception {
        // Create test files
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot);

        var projectFile = new ProjectFile(repoRoot, "src/main/java/Test.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class Test {}");

        var externalFile = new ExternalFile(tempDir.resolve("external.txt").toAbsolutePath());
        Files.writeString(externalFile.absPath(), "This is external content");

        // Create context with fragments
        Context context = new Context(mockContextManager)
                .addEditableFiles(List.of(new ContextFragment.ProjectPathFragment(projectFile)))
                .addReadonlyFiles(List.of(new ContextFragment.ExternalPathFragment(externalFile)))
                .addVirtualFragment(new ContextFragment.StringFragment("virtual content", "Virtual Fragment", SyntaxConstants.SYNTAX_STYLE_NONE));

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json, "JSON serialization should not return null");
        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify fragment counts
        assertEquals(1, deserialized.editableFiles.size());
        assertEquals(1, deserialized.readonlyFiles.size());
        assertEquals(1, deserialized.virtualFragments.size());

        // Check that editable files were properly serialized
        ContextFragment.ProjectPathFragment deserializedEditable = deserialized.editableFiles.get(0);
        assertEquals(projectFile.toString(), deserializedEditable.file().toString());
        assertEquals(projectFile.getRoot(), deserializedEditable.file().getRoot());
        assertEquals(projectFile.getRelPath(), deserializedEditable.file().getRelPath());

        // Check that readonly files were properly serialized
        ContextFragment.PathFragment deserializedReadonly = deserialized.readonlyFiles.get(0);
        assertEquals(externalFile.toString(), deserializedReadonly.file().toString());
        assertInstanceOf(ContextFragment.ExternalPathFragment.class, deserializedReadonly);

        // Check that virtual fragments were properly serialized
        ContextFragment.VirtualFragment deserializedVirtual = deserialized.virtualFragments.get(0);
        assertInstanceOf(ContextFragment.StringFragment.class, deserializedVirtual);
        assertEquals("virtual content", deserializedVirtual.text());
        assertEquals("Virtual Fragment", deserializedVirtual.description());

        // Verify the files can still be read correctly after JSON round-trip
        assertDoesNotThrow(() -> {
            String editableContent = deserializedEditable.text();
            assertEquals("public class Test {}", editableContent);
            
            String readonlyContent = deserializedReadonly.text();
            assertEquals("This is external content", readonlyContent);
        });
    }

    @Test
    void testJsonSerializationWithTaskHistory() throws Exception {
        // Create context
        Context context = new Context(mockContextManager);

        // Create sample chat messages for a full session
        List<ChatMessage> sessionMessages = List.of(
                dev.langchain4j.data.message.UserMessage.from("What is 2+2?"),
                dev.langchain4j.data.message.AiMessage.from("2+2 equals 4.")
        );

        // Add history entry
        var originalContents = Map.<ProjectFile, String>of();
        var parsedOutput = new ContextFragment.TaskFragment(sessionMessages, "Math Question");
        Future<String> action = CompletableFuture.completedFuture("Math Question");
        var result = new SessionResult("What is 2+2?", parsedOutput, Map.of(), new SessionResult.StopDetails(SessionResult.StopReason.SUCCESS));
        var taskEntry = context.createTaskEntry(result);
        context = context.addHistoryEntry(taskEntry, parsedOutput, action, originalContents);

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json, "JSON serialization should not return null");
        assertTrue(json.contains("taskHistory"), "JSON should contain taskHistory field");
        
        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify task history was preserved
        assertEquals(1, deserialized.getTaskHistory().size(), "Task history should contain one entry");
        
        TaskEntry deserializedTask = deserialized.getTaskHistory().get(0);
        assertEquals(taskEntry.sequence(), deserializedTask.sequence(), "Task sequence should be preserved");
        assertNotNull(deserializedTask.log(), "Task log should not be null");
        assertEquals(sessionMessages.size(), deserializedTask.log().messages().size(), "Message count should be preserved");
        
        // Check first message content
        var firstMessage = deserializedTask.log().messages().get(0);
        assertTrue(firstMessage instanceof dev.langchain4j.data.message.UserMessage, "First message should be UserMessage");
        assertEquals("What is 2+2?", ((dev.langchain4j.data.message.UserMessage) firstMessage).singleText());
        
        // Check second message content
        var secondMessage = deserializedTask.log().messages().get(1);
        assertTrue(secondMessage instanceof dev.langchain4j.data.message.AiMessage, "Second message should be AiMessage");
        assertEquals("2+2 equals 4.", ((dev.langchain4j.data.message.AiMessage) secondMessage).text());
        
        assertNull(deserializedTask.summary(), "Task should not be summarized");
    }

    @Test
    void testJsonSerializationWithComplexFragments() throws Exception {
        // Create context with various fragment types
        Context context = new Context(mockContextManager);

        // Create mock file for CodeUnit construction
        ProjectFile mockFile = new ProjectFile(tempDir, "Mock.java");
        Files.createFile(mockFile.absPath());

        // Add SearchFragment
        var searchSources = Set.of(CodeUnit.cls(mockFile, "com.test", "TestClass"));
        context = context.addVirtualFragment(new ContextFragment.SearchFragment("test query", "test explanation", searchSources));

        // Add SkeletonFragment
        var skeletonMap = Map.of(CodeUnit.cls(mockFile, "com.test", "TestClass"), "class TestClass {}");
        context = context.addVirtualFragment(new ContextFragment.SkeletonFragment(skeletonMap));

        // Add UsageFragment
        context = context.addVirtualFragment(new ContextFragment.UsageFragment("TestClass.method", searchSources, "TestClass.method()"));

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json, "JSON serialization should not return null");
        
        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify all fragments were preserved (should be 3: SearchFragment, SkeletonFragment, UsageFragment)
        assertEquals(3, deserialized.virtualFragments.size(), "All virtual fragments should be preserved");

        // Check that we have the expected fragment types
        var fragmentTypes = deserialized.virtualFragments.stream()
                .map(Object::getClass)
                .collect(Collectors.toSet());
        
        assertTrue(fragmentTypes.contains(ContextFragment.SearchFragment.class), "Should contain SearchFragment");
        assertTrue(fragmentTypes.contains(ContextFragment.SkeletonFragment.class), "Should contain SkeletonFragment");
        assertTrue(fragmentTypes.contains(ContextFragment.UsageFragment.class), "Should contain UsageFragment");

        // Verify SearchFragment content
        var searchFragment = (ContextFragment.SearchFragment) deserialized.virtualFragments.stream()
                .filter(f -> f instanceof ContextFragment.SearchFragment)
                .findFirst()
                .orElseThrow();
        assertEquals("Search: test query", searchFragment.description());
        assertTrue(searchFragment.text().contains("test query"));
        assertTrue(searchFragment.text().contains("test explanation"));

        // Verify SkeletonFragment content
        var skeletonFragment = (ContextFragment.SkeletonFragment) deserialized.virtualFragments.stream()
                .filter(f -> f instanceof ContextFragment.SkeletonFragment)
                .findFirst()
                .orElseThrow();
        assertFalse(skeletonFragment.skeletons().isEmpty());
        assertTrue(skeletonFragment.text().contains("TestClass"));

        // Verify UsageFragment content
        var usageFragment = (ContextFragment.UsageFragment) deserialized.virtualFragments.stream()
                .filter(f -> f instanceof ContextFragment.UsageFragment)
                .findFirst()
                .orElseThrow();
        assertEquals("Uses of TestClass.method", usageFragment.description());
        assertEquals("TestClass.method()", usageFragment.text());
    }

    @Test
    void testJsonSerializationPreservesOrder() throws Exception {
        // Create context with multiple fragments of the same type
        Context context = new Context(mockContextManager);

        // Add multiple string fragments in specific order
        context = context.addVirtualFragment(new ContextFragment.StringFragment("first", "First Fragment", SyntaxConstants.SYNTAX_STYLE_NONE));
        context = context.addVirtualFragment(new ContextFragment.StringFragment("second", "Second Fragment", SyntaxConstants.SYNTAX_STYLE_NONE));
        context = context.addVirtualFragment(new ContextFragment.StringFragment("third", "Third Fragment", SyntaxConstants.SYNTAX_STYLE_NONE));

        // Serialize to JSON and deserialize
        String json = context.toJson();
        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify order is preserved
        assertEquals(3, deserialized.virtualFragments.size());
        assertEquals("first", deserialized.virtualFragments.get(0).text());
        assertEquals("second", deserialized.virtualFragments.get(1).text());
        assertEquals("third", deserialized.virtualFragments.get(2).text());
    }

    @Test
    void testJsonSerializationHandlesEmptyContext() throws Exception {
        // Create completely empty context
        Context context = new Context(mockContextManager);

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json);
        assertTrue(json.contains("editableFiles"), "JSON should contain editableFiles field");
        assertTrue(json.contains("readonlyFiles"), "JSON should contain readonlyFiles field");
        assertTrue(json.contains("virtualFragments"), "JSON should contain virtualFragments field");
        assertTrue(json.contains("taskHistory"), "JSON should contain taskHistory field");

        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify everything is empty
        assertTrue(deserialized.editableFiles.isEmpty());
        assertTrue(deserialized.readonlyFiles.isEmpty());
        assertTrue(deserialized.virtualFragments.isEmpty());
        assertTrue(deserialized.taskHistory.isEmpty());
        assertTrue(deserialized.isEmpty());
    }
 }
