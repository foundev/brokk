package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.Json;
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

    @Test
    void testJsonSerializationWithPasteFragments() throws Exception {
        Context context = new Context(mockContextManager);

        // Create PasteTextFragment with completed future
        CompletableFuture<String> textDescFuture = CompletableFuture.completedFuture("code snippet");
        var pasteTextFragment = new ContextFragment.PasteTextFragment("int x = 5;", textDescFuture);
        context = context.addVirtualFragment(pasteTextFragment);

        // Create PasteImageFragment with completed future
        CompletableFuture<String> imageDescFuture = CompletableFuture.completedFuture("diagram");
        // Create a simple test image
        var testImage = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var pasteImageFragment = new ContextFragment.PasteImageFragment(testImage, imageDescFuture);
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
        var imageFileFragment = new ContextFragment.ImageFileFragment(externalFile);

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
        var codeUnits = Set.of(CodeUnit.cls(mockFile, "com.test", "TestClass"));
        var callGraphFragment = new ContextFragment.CallGraphFragment(
            "Callers", 
            "TestClass.method", 
            codeUnits, 
            "TestClass.method() calls:\n  OtherClass.helper()"
        );

        context = context.addVirtualFragment(callGraphFragment);

        // Serialize to JSON and deserialize
        String json = context.toJson();
        assertNotNull(json);

        Context deserialized = Context.fromJson(json, mockContextManager);

        // Verify CallGraphFragment was preserved
        assertEquals(1, deserialized.virtualFragments.size());
        var deserializedFragment = (ContextFragment.CallGraphFragment) deserialized.virtualFragments.get(0);
        
        assertEquals("Callers of TestClass.method", deserializedFragment.description());
        assertEquals("TestClass.method() calls:\n  OtherClass.helper()", deserializedFragment.text());
        assertEquals(1, deserializedFragment.sources(null).size());
    }

    @Test
    void testJsonSerializationWithHistoryFragment() throws Exception {
        Context context = new Context(mockContextManager);

        // Create some task entries
        List<ChatMessage> messages1 = List.of(
            dev.langchain4j.data.message.UserMessage.from("First question"),
            dev.langchain4j.data.message.AiMessage.from("First answer")
        );
        var taskFragment1 = new ContextFragment.TaskFragment(messages1, "Task 1");
        var taskEntry1 = new TaskEntry(1, taskFragment1, null);

        List<ChatMessage> messages2 = List.of(
            dev.langchain4j.data.message.UserMessage.from("Second question"),
            dev.langchain4j.data.message.AiMessage.from("Second answer")
        );
        var taskFragment2 = new ContextFragment.TaskFragment(messages2, "Task 2");
        var taskEntry2 = new TaskEntry(2, taskFragment2, null);

        var historyFragment = new ContextFragment.HistoryFragment(List.of(taskEntry1, taskEntry2));
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
        var stringFragment = new ContextFragment.StringFragment(
            specialText, 
            "Fragment with émojis & spéciäl chars", 
            SyntaxConstants.SYNTAX_STYLE_NONE
        );
        context = context.addVirtualFragment(stringFragment);

        // Test with file paths containing special characters
        Path repoRoot = tempDir.resolve("project with spaces & symbols");
        Files.createDirectories(repoRoot);
        var specialFile = new ProjectFile(repoRoot, "src/Special File (1).java");
        context = context.addEditableFiles(List.of(new ContextFragment.ProjectPathFragment(specialFile)));

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
        context = context.addEditableFiles(List.of(new ContextFragment.ProjectPathFragment(projectFile)));

        // Add ExternalFile
        var externalFile = new ExternalFile(tempDir.resolve("external.txt").toAbsolutePath());
        Files.writeString(externalFile.absPath(), "External content");
        context = context.addReadonlyFiles(List.of(new ContextFragment.ExternalPathFragment(externalFile)));

        // Add StringFragment
        context = context.addVirtualFragment(new ContextFragment.StringFragment(
            "String content", "String Description", SyntaxConstants.SYNTAX_STYLE_JAVA));

        // Add SearchFragment
        var searchSources = Set.of(CodeUnit.cls(projectFile, "com.test", "Complex"));
        context = context.addVirtualFragment(new ContextFragment.SearchFragment(
            "search query", "search result", searchSources));

        // Add SkeletonFragment
        var skeletonMap = Map.of(CodeUnit.cls(projectFile, "com.test", "Complex"), "class Complex {}");
        context = context.addVirtualFragment(new ContextFragment.SkeletonFragment(skeletonMap));

        // Add UsageFragment
        context = context.addVirtualFragment(new ContextFragment.UsageFragment(
            "Complex.method", searchSources, "Complex.method() usage"));

        // Add TaskHistory
        List<ChatMessage> sessionMessages = List.of(
            dev.langchain4j.data.message.UserMessage.from("Test question"),
            dev.langchain4j.data.message.AiMessage.from("Test answer")
        );
        var taskFragment = new ContextFragment.TaskFragment(sessionMessages, "Integration Test");
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
 }
