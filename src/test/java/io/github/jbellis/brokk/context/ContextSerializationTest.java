package io.github.jbellis.brokk.context;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.SessionResult;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.SessionResult;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.AnalyzerWrapper;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
            @Override
            public AnalyzerWrapper getAnalyzerWrapper() { // Use imported AnalyzerWrapper
                return null; // Allow tests to proceed with fragments that try to access analyzer
            }
        };
        // Clear the intern pool before each test to ensure isolation
        FrozenFragment.clearInternPoolForTesting();
    }

    @Test
    void testContextJsonSerialization() throws Exception {
        // Create a context with minimal state
        Context context = new Context(mockContextManager);

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json, "JSON serialization should not return null");
        assertTrue(json.contains("editableFiles"), "JSON should contain editableFiles field");

        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify non-transient fields were preserved
        assertEquals(context.editableFiles.size(), deserialized.editableFiles.size());
        assertEquals(context.readonlyFiles.size(), deserialized.readonlyFiles.size());
        assertEquals(context.virtualFragments.size(), deserialized.virtualFragments.size());
        assertEquals(context.taskHistory.size(), deserialized.taskHistory.size());

        // Transient fields should be initialized appropriately
        assertNotNull(deserialized.getContextManager());
        assertEquals(mockContextManager, deserialized.getContextManager());
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
                .addEditableFiles(List.of(new ContextFragment.ProjectPathFragment(projectFile, mockContextManager)))
                .addReadonlyFiles(List.of(new ContextFragment.ExternalPathFragment(externalFile, mockContextManager)))
                .addVirtualFragment(new ContextFragment.StringFragment(mockContextManager, "virtual content", "Virtual Fragment", SyntaxConstants.SYNTAX_STYLE_NONE));

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
        var parsedOutput = new ContextFragment.TaskFragment(mockContextManager, sessionMessages, "Math Question");
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
        List<ChatMessage> searchMessages = List.of(
                dev.langchain4j.data.message.UserMessage.from("test query"),
                dev.langchain4j.data.message.AiMessage.from("test explanation")
        );
        context = context.addVirtualFragment(new ContextFragment.SearchFragment(mockContextManager, "Search: test query", searchMessages, searchSources));

        // Add SkeletonFragment
        var skeletonMap = Map.of(CodeUnit.cls(mockFile, "com.test", "TestClass"), "class TestClass {}");
        var targetIdentifiers = skeletonMap.keySet().stream().map(CodeUnit::fqName).toList();
        context = context.addVirtualFragment(new ContextFragment.SkeletonFragment(mockContextManager, targetIdentifiers, ContextFragment.SummaryType.CLASS_SKELETON));

        // Add UsageFragment
        context = context.addVirtualFragment(new ContextFragment.UsageFragment(mockContextManager, "TestClass.method"));

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
        assertEquals("Search: test query", searchFragment.description()); // description is sessionName
        assertTrue(searchFragment.text().contains("test query")); // text() formats underlying messages
        assertTrue(searchFragment.text().contains("test explanation"));

        // Verify SkeletonFragment content
        var skeletonFragment = (ContextFragment.SkeletonFragment) deserialized.virtualFragments.stream()
                .filter(f -> f instanceof ContextFragment.SkeletonFragment)
                .findFirst()
                .orElseThrow();
        assertFalse(skeletonFragment.getTargetIdentifiers().isEmpty());
        assertTrue(skeletonFragment.text().contains("TestClass"));

        // Verify UsageFragment content
        var usageFragment = (ContextFragment.UsageFragment) deserialized.virtualFragments.stream()
                .filter(f -> f instanceof ContextFragment.UsageFragment)
                .findFirst()
                .orElseThrow();
        assertEquals("Uses of TestClass.method", usageFragment.description());
        // Text is dynamically generated, check it contains the identifier
        assertTrue(usageFragment.text().contains("TestClass.method"));
    }

    @Test
    void testJsonSerializationPreservesOrder() throws Exception {
        // Create context with multiple fragments of the same type
        Context context = new Context(mockContextManager);

        // Add multiple string fragments in specific order
        context = context.addVirtualFragment(new ContextFragment.StringFragment(mockContextManager, "first", "First Fragment", SyntaxConstants.SYNTAX_STYLE_NONE));
        context = context.addVirtualFragment(new ContextFragment.StringFragment(mockContextManager, "second", "Second Fragment", SyntaxConstants.SYNTAX_STYLE_NONE));
        context = context.addVirtualFragment(new ContextFragment.StringFragment(mockContextManager, "third", "Third Fragment", SyntaxConstants.SYNTAX_STYLE_NONE));

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

    @Test
    void testJsonSerializationWithPasteFragments() throws Exception {
        Context context = new Context(mockContextManager);

        // Create PasteTextFragment with completed future
        CompletableFuture<String> textDescFuture = CompletableFuture.completedFuture("code snippet");
        var pasteTextFragment = new ContextFragment.PasteTextFragment(mockContextManager, "int x = 5;", textDescFuture);
        context = context.addVirtualFragment(pasteTextFragment);

        // Create PasteImageFragment with completed future
        CompletableFuture<String> imageDescFuture = CompletableFuture.completedFuture("diagram");
        // Create a simple test image
        var testImage = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var pasteImageFragment = new ContextFragment.PasteImageFragment(mockContextManager, testImage, imageDescFuture);
        context = context.addVirtualFragment(pasteImageFragment);

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json);
        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify fragments were preserved
        assertEquals(2, deserialized.virtualFragments.size());

        // Find PasteTextFragment
        var deserializedTextFragment = (ContextFragment.PasteTextFragment) deserialized.virtualFragments.stream()
                .filter(f -> f instanceof ContextFragment.PasteTextFragment)
                .findFirst()
                .orElseThrow();
        assertEquals("int x = 5;", deserializedTextFragment.text());
        assertEquals("Paste of code snippet", deserializedTextFragment.description());

        // Find PasteImageFragment
        var deserializedImageFragment = (ContextFragment.PasteImageFragment) deserialized.virtualFragments.stream()
                .filter(f -> f instanceof ContextFragment.PasteImageFragment)
                .findFirst()
                .orElseThrow();
        assertEquals("Paste of diagram", deserializedImageFragment.description());
        assertNotNull(deserializedImageFragment.image());
        assertFalse(deserializedImageFragment.isText());
    }

    @Test
    void testJsonSerializationWithGitFileFragment() throws Exception {
        // Create test ProjectFile
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot);
        var projectFile = new ProjectFile(repoRoot, "src/Test.java");
        
        // Create GitFileFragment
        var gitFileFragment = new ContextFragment.GitFileFragment(
            projectFile, 
            "abc123def", 
            "public class OldTest {}"
        );

        Context context = new Context(mockContextManager)
                .addReadonlyFiles(List.of(gitFileFragment));

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json);
        assertTrue(json.contains("revision"), "JSON should contain revision field");
        assertTrue(json.contains("abc123def"), "JSON should contain revision value");

        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify GitFileFragment was preserved
        assertEquals(1, deserialized.readonlyFiles.size());
        var deserializedFragment = (ContextFragment.GitFileFragment) deserialized.readonlyFiles.get(0);
        
        assertEquals("abc123def", deserializedFragment.revision());
        assertEquals("public class OldTest {}", deserializedFragment.content());
        assertEquals(projectFile.toString(), deserializedFragment.file().toString());
        assertTrue(deserializedFragment.description().contains("@abc123d")); // short revision
    }

    @Test
    void testJsonSerializationWithImageFileFragment() throws Exception {
        // Create test image file
        Path imagePath = tempDir.resolve("test.png").toAbsolutePath();
        Files.createFile(imagePath);
        var externalFile = new ExternalFile(imagePath);
        
        // Create ImageFileFragment
        var imageFileFragment = new ContextFragment.ImageFileFragment(externalFile, mockContextManager);

        Context context = new Context(mockContextManager)
                .addReadonlyFiles(List.of(imageFileFragment));

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json);

        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify ImageFileFragment was preserved
        assertEquals(1, deserialized.readonlyFiles.size());
        var deserializedFragment = (ContextFragment.ImageFileFragment) deserialized.readonlyFiles.get(0);
        
        assertEquals(imagePath.toString(), deserializedFragment.file().toString());
        assertEquals("test.png", deserializedFragment.file().getFileName());
        assertFalse(deserializedFragment.isText());
        assertEquals("[Image content provided out of band]", deserializedFragment.text());
    }

    @Test
    void testJsonSerializationWithCallGraphFragment() throws Exception {
        Context context = new Context(mockContextManager);
        ProjectFile mockFile = new ProjectFile(tempDir, "Test.java");
        Files.createFile(mockFile.absPath());

        // Create CallGraphFragment
        // var codeUnits = Set.of(CodeUnit.cls(mockFile, "com.test", "TestClass")); // No longer a direct constructor param
        var callGraphFragment = new ContextFragment.CallGraphFragment(mockContextManager, "TestClass.method", 1, false); // Assuming depth 1, isCalleeGraph=false for "Callers"

        context = context.addVirtualFragment(callGraphFragment);

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json);

        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify CallGraphFragment was preserved
        assertEquals(1, deserialized.virtualFragments.size());
        var deserializedFragment = (ContextFragment.CallGraphFragment) deserialized.virtualFragments.get(0);
        
        assertEquals("Callers of TestClass.method (depth 1)", deserializedFragment.description());
        // Text is dynamically generated
        assertTrue(deserializedFragment.text().contains("TestClass.method"));
        // Sources are also dynamic - CallGraphFragment returns sources based on analyzer state
        // With our mock setup (no analyzer), it returns empty set
        assertEquals(0, deserializedFragment.sources().size());
    }

    @Test
    void testJsonSerializationWithHistoryFragment() throws Exception {
        Context context = new Context(mockContextManager);

        // Create some task entries
        List<ChatMessage> messages1 = List.of(
            dev.langchain4j.data.message.UserMessage.from("First question"),
            dev.langchain4j.data.message.AiMessage.from("First answer")
        );
        var taskFragment1 = new ContextFragment.TaskFragment(mockContextManager, messages1, "Task 1");
        var taskEntry1 = new TaskEntry(1, taskFragment1, null);

        List<ChatMessage> messages2 = List.of(
            dev.langchain4j.data.message.UserMessage.from("Second question"),
            dev.langchain4j.data.message.AiMessage.from("Second answer")
        );
        var taskFragment2 = new ContextFragment.TaskFragment(mockContextManager, messages2, "Task 2");
        var taskEntry2 = new TaskEntry(2, taskFragment2, null);

        var historyFragment = new ContextFragment.HistoryFragment(mockContextManager, List.of(taskEntry1, taskEntry2));
        context = context.addVirtualFragment(historyFragment);

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json);

        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify HistoryFragment was preserved
        assertEquals(1, deserialized.virtualFragments.size());
        var deserializedFragment = (ContextFragment.HistoryFragment) deserialized.virtualFragments.get(0);
        
        assertEquals("Task History (2 tasks)", deserializedFragment.description());
        assertEquals(2, deserializedFragment.entries().size());
        
        // Check first task entry
        var firstEntry = deserializedFragment.entries().get(0);
        assertEquals(1, firstEntry.sequence());
        assertNotNull(firstEntry.log());
        assertEquals("Task 1", firstEntry.log().description());
        assertEquals(2, firstEntry.log().messages().size());
    }

    @Test
    void testJsonSerializationWithSpecialCharacters() throws Exception {
        Context context = new Context(mockContextManager);

        // Create fragments with special characters
        String specialText = "Special chars: äöü ñ 中文 🎉 \"quotes\" 'apostrophes' \n\t\r";
        var stringFragment = new ContextFragment.StringFragment(mockContextManager,
            specialText,
            "Fragment with émojis & spéciäl chars",
            SyntaxConstants.SYNTAX_STYLE_NONE
        );
        context = context.addVirtualFragment(stringFragment);

        // Test with file paths containing special characters
        Path repoRoot = tempDir.resolve("project with spaces & symbols");
        Files.createDirectories(repoRoot);
        var specialFile = new ProjectFile(repoRoot, "src/Special File (1).java");
        context = context.addEditableFiles(List.of(new ContextFragment.ProjectPathFragment(specialFile, mockContextManager)));

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json);
        // Verify JSON contains escaped special characters properly
        assertTrue(json.contains("Special chars"));

        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify special characters were preserved
        assertEquals(1, deserialized.virtualFragments.size());
        var deserializedStringFragment = (ContextFragment.StringFragment) deserialized.virtualFragments.get(0);
        assertEquals(specialText, deserializedStringFragment.text());
        assertEquals("Fragment with émojis & spéciäl chars", deserializedStringFragment.description());

        assertEquals(1, deserialized.editableFiles.size());
        var deserializedFileFragment = deserialized.editableFiles.get(0);
        assertTrue(deserializedFileFragment.file().toString().contains("Special File (1).java"));
    }


    @Test
    void testJsonSerializationRoundTripIntegrity() throws Exception {
        // Create a complex context with multiple fragment types
        Context context = new Context(mockContextManager);

        // Add ProjectFile
        Path repoRoot = tempDir.resolve("complex-project");
        Files.createDirectories(repoRoot);
        var projectFile = new ProjectFile(repoRoot, "src/main/java/Complex.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class Complex {}");
        context = context.addEditableFiles(List.of(new ContextFragment.ProjectPathFragment(projectFile, mockContextManager)));

        // Add ExternalFile
        var externalFile = new ExternalFile(tempDir.resolve("external.txt").toAbsolutePath());
        Files.writeString(externalFile.absPath(), "External content");
        context = context.addReadonlyFiles(List.of(new ContextFragment.ExternalPathFragment(externalFile, mockContextManager)));

        // Add StringFragment
        context = context.addVirtualFragment(new ContextFragment.StringFragment(mockContextManager,
            "String content", "String Description", SyntaxConstants.SYNTAX_STYLE_JAVA));

        // Add SearchFragment
        var searchSources = Set.of(CodeUnit.cls(projectFile, "com.test", "Complex"));
        List<ChatMessage> complexSearchMessages = List.of(
                dev.langchain4j.data.message.UserMessage.from("search query"),
                dev.langchain4j.data.message.AiMessage.from("search result")
        );
        context = context.addVirtualFragment(new ContextFragment.SearchFragment(mockContextManager,
            "Search: search query", complexSearchMessages, searchSources));

        // Add SkeletonFragment
        var skeletonMap = Map.of(CodeUnit.cls(projectFile, "com.test", "Complex"), "class Complex {}");
        var targetIdentifiers = skeletonMap.keySet().stream().map(CodeUnit::fqName).toList();
        context = context.addVirtualFragment(new ContextFragment.SkeletonFragment(mockContextManager, targetIdentifiers, ContextFragment.SummaryType.CLASS_SKELETON));

        // Add UsageFragment
        context = context.addVirtualFragment(new ContextFragment.UsageFragment(mockContextManager, "Complex.method"));

        // Add TaskHistory
        List<ChatMessage> sessionMessages = List.of(
            dev.langchain4j.data.message.UserMessage.from("Test question"),
            dev.langchain4j.data.message.AiMessage.from("Test answer")
        );
        var taskFragment = new ContextFragment.TaskFragment(mockContextManager, sessionMessages, "Integration Test");
        var result = new SessionResult("Test question", taskFragment, Map.of(),
            new SessionResult.StopDetails(SessionResult.StopReason.SUCCESS));
        var taskEntry = context.createTaskEntry(result);
        context = context.addHistoryEntry(taskEntry, taskFragment, 
            CompletableFuture.completedFuture("Integration Test"), Map.of());

        // Perform multiple round-trips
        String json1 = context.toJson();
        Context deserialized1 = Context.fromJson(json1, mockContextManager);
        String json2 = deserialized1.toJson();
        Context deserialized2 = Context.fromJson(json2, mockContextManager);

        // Verify consistency across round-trips
        assertEquals(context.editableFiles.size(), deserialized1.editableFiles.size());
        assertEquals(context.editableFiles.size(), deserialized2.editableFiles.size());
        
        assertEquals(context.readonlyFiles.size(), deserialized1.readonlyFiles.size());
        assertEquals(context.readonlyFiles.size(), deserialized2.readonlyFiles.size());
        
        assertEquals(context.virtualFragments.size(), deserialized1.virtualFragments.size());
        assertEquals(context.virtualFragments.size(), deserialized2.virtualFragments.size());
        
        assertEquals(context.taskHistory.size(), deserialized1.taskHistory.size());
        assertEquals(context.taskHistory.size(), deserialized2.taskHistory.size());

        // Verify that JSON representations are stable
        // Note: Order and exact formatting might vary, but structure should be consistent
        assertNotNull(json1);
        assertNotNull(json2);
        assertTrue(json1.length() > 100); // Sanity check for non-trivial content
        assertTrue(json2.length() > 100);
    }

    @Test
    void testFrozenFragmentInterning_SameContentSameInstance_StringFragment() throws Exception {
        var liveFragment1 = new ContextFragment.StringFragment(mockContextManager, "test content", "desc1", SyntaxConstants.SYNTAX_STYLE_NONE);
        var liveFragment2 = new ContextFragment.StringFragment(mockContextManager, "test content", "desc1", SyntaxConstants.SYNTAX_STYLE_NONE);

        FrozenFragment frozen1 = FrozenFragment.freeze(liveFragment1, mockContextManager);
        FrozenFragment frozen2 = FrozenFragment.freeze(liveFragment2, mockContextManager);

        assertSame(frozen1, frozen2, "FrozenFragments with identical content and metadata should be the same instance.");
        assertEquals(liveFragment1.id(), frozen1.id(), "ID of interned fragment should be from the first live fragment.");
        assertEquals(frozen1.id(), frozen2.id(), "IDs of interned fragments should be identical.");
        assertEquals(frozen1.getContentHash(), frozen2.getContentHash(), "Content hashes should be identical.");
    }

    @Test
    void testFrozenFragmentInterning_DifferentContentDifferentInstances_StringFragment() throws Exception {
        var liveFragment1 = new ContextFragment.StringFragment(mockContextManager, "content1", "desc1", SyntaxConstants.SYNTAX_STYLE_NONE);
        var liveFragment2 = new ContextFragment.StringFragment(mockContextManager, "content2", "desc1", SyntaxConstants.SYNTAX_STYLE_NONE);

        FrozenFragment frozen1 = FrozenFragment.freeze(liveFragment1, mockContextManager);
        FrozenFragment frozen2 = FrozenFragment.freeze(liveFragment2, mockContextManager);

        assertNotSame(frozen1, frozen2, "FrozenFragments with different content should be different instances.");
        assertEquals(liveFragment1.id(), frozen1.id());
        assertEquals(liveFragment2.id(), frozen2.id());
        assertNotEquals(frozen1.getContentHash(), frozen2.getContentHash(), "Content hashes should be different.");
    }

    @Test
    void testFrozenFragmentInterning_SameContentDifferentDescription_StringFragment() throws Exception {
        var liveFragment1 = new ContextFragment.StringFragment(mockContextManager, "test content", "description ONE", SyntaxConstants.SYNTAX_STYLE_NONE);
        var liveFragment2 = new ContextFragment.StringFragment(mockContextManager, "test content", "description TWO", SyntaxConstants.SYNTAX_STYLE_NONE);

        FrozenFragment frozen1 = FrozenFragment.freeze(liveFragment1, mockContextManager);
        FrozenFragment frozen2 = FrozenFragment.freeze(liveFragment2, mockContextManager);

        assertNotSame(frozen1, frozen2, "FrozenFragments with different descriptions should be different instances.");
        assertEquals(liveFragment1.id(), frozen1.id());
        assertEquals(liveFragment2.id(), frozen2.id());
        assertNotEquals(frozen1.getContentHash(), frozen2.getContentHash(), "Content hashes should be different for different descriptions.");
    }

    @Test
    void testFrozenFragmentInterning_SameContentDifferentSyntaxStyle_StringFragment() throws Exception {
        var liveFragment1 = new ContextFragment.StringFragment(mockContextManager, "test content", "desc", SyntaxConstants.SYNTAX_STYLE_JAVA);
        var liveFragment2 = new ContextFragment.StringFragment(mockContextManager, "test content", "desc", SyntaxConstants.SYNTAX_STYLE_PYTHON);

        FrozenFragment frozen1 = FrozenFragment.freeze(liveFragment1, mockContextManager);
        FrozenFragment frozen2 = FrozenFragment.freeze(liveFragment2, mockContextManager);

        assertNotSame(frozen1, frozen2, "FrozenFragments with different syntax styles should be different instances.");
        assertEquals(liveFragment1.id(), frozen1.id());
        assertEquals(liveFragment2.id(), frozen2.id());
        assertNotEquals(frozen1.getContentHash(), frozen2.getContentHash(), "Content hashes should be different for different syntax styles.");
    }

    @Test
    void testFrozenFragmentInterning_ProjectPathFragment_SameContent() throws Exception {
        Path fileAPath = tempDir.resolve("fileA.txt");
        Files.writeString(fileAPath, "Common content for ProjectPathFragment");

        var liveFragment1 = new ContextFragment.ProjectPathFragment(new ProjectFile(tempDir, "fileA.txt"), mockContextManager);
        // Create a new ProjectPathFragment instance for the same file, to simulate different live fragments pointing to same content
        var liveFragment2 = new ContextFragment.ProjectPathFragment(new ProjectFile(tempDir, "fileA.txt"), mockContextManager);

        FrozenFragment frozen1 = FrozenFragment.freeze(liveFragment1, mockContextManager);
        FrozenFragment frozen2 = FrozenFragment.freeze(liveFragment2, mockContextManager);

        assertSame(frozen1, frozen2, "Frozen ProjectPathFragments with identical file content should be the same instance.");
        assertEquals(liveFragment1.id(), frozen1.id(), "ID of interned fragment should be from the first live fragment.");
        assertEquals(frozen1.getContentHash(), frozen2.getContentHash(), "Content hashes should be identical.");

        // Modify content and check new frozen fragment
        Files.writeString(fileAPath, "Modified content");
        var liveFragment3 = new ContextFragment.ProjectPathFragment(new ProjectFile(tempDir, "fileA.txt"), mockContextManager);
        FrozenFragment frozen3 = FrozenFragment.freeze(liveFragment3, mockContextManager);

        assertNotSame(frozen1, frozen3, "FrozenFragment should be different after content modification.");
        assertNotEquals(frozen1.getContentHash(), frozen3.getContentHash(), "Content hashes should differ after content modification.");
    }

    private java.awt.image.BufferedImage createTestImage(java.awt.Color color) {
        var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 1, 1);
        g.dispose();
        return image;
    }

    @Test
    void testFrozenFragmentInterning_PasteImageFragment_SameImage() throws Exception {
        java.awt.image.BufferedImage image1Data = createTestImage(java.awt.Color.RED);
        java.awt.image.BufferedImage image2Data = createTestImage(java.awt.Color.RED); // Same content

        var liveFragment1 = new ContextFragment.PasteImageFragment(mockContextManager, image1Data, CompletableFuture.completedFuture("Image Description"));
        var liveFragment2 = new ContextFragment.PasteImageFragment(mockContextManager, image2Data, CompletableFuture.completedFuture("Image Description"));

        FrozenFragment frozen1 = FrozenFragment.freeze(liveFragment1, mockContextManager);
        FrozenFragment frozen2 = FrozenFragment.freeze(liveFragment2, mockContextManager);

        assertSame(frozen1, frozen2, "Frozen PasteImageFragments with identical image data should be the same instance.");
        assertEquals(liveFragment1.id(), frozen1.id(), "ID of interned fragment should be from the first live fragment.");
        assertEquals(frozen1.getContentHash(), frozen2.getContentHash(), "Content hashes should be identical.");

        // Different image
        java.awt.image.BufferedImage image3Data = createTestImage(java.awt.Color.BLUE);
        var liveFragment3 = new ContextFragment.PasteImageFragment(mockContextManager, image3Data, CompletableFuture.completedFuture("Image Description"));
        FrozenFragment frozen3 = FrozenFragment.freeze(liveFragment3, mockContextManager);

        assertNotSame(frozen1, frozen3, "FrozenFragment should be different for different image data.");
        assertNotEquals(frozen1.getContentHash(), frozen3.getContentHash(), "Content hashes should differ for different image data.");
    }

    @Test
    void testFrozenFragmentInterning_GitFileFragment_SameContentRevision() throws Exception {
        Path repoRoot = tempDir.resolve("git-repo");
        Files.createDirectories(repoRoot);
        ProjectFile pf = new ProjectFile(repoRoot, "file.txt");

        var liveFragment1 = new ContextFragment.GitFileFragment(pf, "rev123", "git content");
        var liveFragment2 = new ContextFragment.GitFileFragment(pf, "rev123", "git content"); // Same revision, same content

        FrozenFragment frozen1 = FrozenFragment.freeze(liveFragment1, mockContextManager);
        FrozenFragment frozen2 = FrozenFragment.freeze(liveFragment2, mockContextManager);

        assertSame(frozen1, frozen2, "Frozen GitFileFragments with same content and revision should be interned.");
        assertEquals(liveFragment1.id(), frozen1.id());
        assertEquals(frozen1.getContentHash(), frozen2.getContentHash());

        // Different content
        var liveFragment3 = new ContextFragment.GitFileFragment(pf, "rev123", "different git content");
        FrozenFragment frozen3 = FrozenFragment.freeze(liveFragment3, mockContextManager);
        assertNotSame(frozen1, frozen3);
        assertNotEquals(frozen1.getContentHash(), frozen3.getContentHash());

        // Different revision
        var liveFragment4 = new ContextFragment.GitFileFragment(pf, "rev456", "git content");
        FrozenFragment frozen4 = FrozenFragment.freeze(liveFragment4, mockContextManager);
        assertNotSame(frozen1, frozen4); // Meta includes revision, so hash will differ
        assertNotEquals(frozen1.getContentHash(), frozen4.getContentHash());
    }
 }
