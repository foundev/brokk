package io.github.jbellis.brokk.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.HistoryIo;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import io.github.jbellis.brokk.util.Messages;

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
            public AnalyzerWrapper getAnalyzerWrapper() {
                return null;
            }
            // Implement other methods if needed by fragments during test execution
        };
        // Clear the intern pool before each test to ensure isolation
        FrozenFragment.clearInternPoolForTesting();
        // Reset fragment ID counter for test isolation
        ContextFragment.nextId.set(1);
    }

    private BufferedImage createTestImage(Color color, int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private byte[] imageToBytes(Image image) throws IOException {
        if (image == null) return null;
        BufferedImage bufferedImage = (image instanceof BufferedImage) ? (BufferedImage) image :
                                      new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        if (!(image instanceof BufferedImage)) {
            Graphics2D bGr = bufferedImage.createGraphics();
            bGr.drawImage(image, 0, 0, null);
            bGr.dispose();
        }
        try (var baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "PNG", baos);
            return baos.toByteArray();
        }
    }

    // --- Tests for HistoryIo ---

    @Test
    void testWriteReadEmptyHistory() throws IOException {
        var history = new ContextHistory();
        Path zipFile = tempDir.resolve("empty_history.zip");

        HistoryIo.writeZip(history, zipFile);
        assertTrue(Files.exists(zipFile));

        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);
        assertNotNull(loadedHistory);
        assertTrue(loadedHistory.getHistory().isEmpty());
    }

    @Test
    void testReadNonExistentZip() throws IOException {
        Path zipFile = tempDir.resolve("non_existent.zip");
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);
        assertNotNull(loadedHistory);
        assertTrue(loadedHistory.getHistory().isEmpty(), "Reading a non-existent zip should result in an empty history.");
    }

    @Test
    void testWriteReadHistoryWithSingleContext_NoFragments() throws IOException {
        var history = new ContextHistory();
        var initialContext = new Context(mockContextManager, "Initial welcome.");
        history.setInitialContext(initialContext);

        Path zipFile = tempDir.resolve("single_context_no_fragments.zip");
        HistoryIo.writeZip(history, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertEquals(1, loadedHistory.getHistory().size());
        // Further assertions can be added to compare context details if necessary,
        // focusing on serializable aspects.
        // For a "no fragments" context, primarily the task history (welcome message) is relevant.
        Context loadedCtx = loadedHistory.getHistory().get(0);
        assertNotNull(loadedCtx.getParsedOutput()); // Welcome message
        assertEquals("Welcome", loadedCtx.getParsedOutput().description());
    }


    @Test
    void testWriteReadHistoryWithComplexContent() throws Exception {
        ContextHistory originalHistory = new ContextHistory();

        // Context 1: Project file, string fragment
        var projectFile1 = new ProjectFile(tempDir, "src/File1.java");
        Files.createDirectories(projectFile1.absPath().getParent());
        Files.writeString(projectFile1.absPath(), "public class File1 {}");
        var context1 = new Context(mockContextManager, "Context 1 started")
                .addEditableFiles(List.of(new ContextFragment.ProjectPathFragment(projectFile1, mockContextManager)))
                .addVirtualFragment(new ContextFragment.StringFragment(mockContextManager, "Virtual content 1", "VC1", SyntaxConstants.SYNTAX_STYLE_JAVA));
        originalHistory.setInitialContext(context1);

        // Context 2: Image fragment, task history
        var image1 = createTestImage(Color.RED, 10, 10);
        var pasteImageFragment1 = new ContextFragment.PasteImageFragment(mockContextManager, image1, CompletableFuture.completedFuture("Pasted Red Image"));
        
        var context2 = new Context(mockContextManager, "Context 2 started")
                .addVirtualFragment(pasteImageFragment1);
        
        List<ChatMessage> taskMessages = List.of(UserMessage.from("User query"), AiMessage.from("AI response"));
        var taskFragment = new ContextFragment.TaskFragment(mockContextManager, taskMessages, "Test Task");
        context2 = context2.addHistoryEntry(new TaskEntry(1, taskFragment, null), taskFragment, CompletableFuture.completedFuture("Action for task"), Map.of());
        
        final Context finalContext2 = context2; // effectively final for lambda
        originalHistory.pushContext(prev -> finalContext2);
        
        Path zipFile = tempDir.resolve("complex_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        // Assertions
        assertEquals(originalHistory.getHistory().size(), loadedHistory.getHistory().size());

        // Compare Context 1 (which will be frozen)
        Context originalCtx1Frozen = originalHistory.getHistory().get(0); // This is already frozen by ContextHistory
        Context loadedCtx1 = loadedHistory.getHistory().get(0);
        assertContextsEqual(originalCtx1Frozen, loadedCtx1);


        // Compare Context 2 (which will be frozen)
        Context originalCtx2Frozen = originalHistory.getHistory().get(1); // This is already frozen by ContextHistory
        Context loadedCtx2 = loadedHistory.getHistory().get(1);
        assertContextsEqual(originalCtx2Frozen, loadedCtx2);

        // Verify image content from a FrozenFragment in loadedCtx2
        var loadedImageFragment = loadedCtx2.virtualFragments()
            .filter(f -> f instanceof FrozenFragment && !f.isText() && "Pasted Red Image".equals(f.description()))
            .map(f -> (FrozenFragment) f)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Pasted Red Image FrozenFragment not found in loaded context 2"));
        
        assertNotNull(loadedImageFragment.imageBytesContent());
        assertTrue(loadedImageFragment.imageBytesContent().length > 0);
        Image loadedImage = ImageIO.read(new java.io.ByteArrayInputStream(loadedImageFragment.imageBytesContent()));
        assertNotNull(loadedImage);
        assertEquals(10, loadedImage.getWidth(null));
        assertEquals(10, loadedImage.getHeight(null));
        // Could do a pixel-by-pixel comparison if necessary
    }

    private void assertContextsEqual(Context expected, Context actual) throws IOException, InterruptedException {
        // Compare editable files (these should be empty if all were dynamic and frozen)
        // After freezing, dynamic ProjectPathFragments become FrozenFragments in virtualFragments.
        // Non-dynamic ones would remain. Assuming test setup makes them dynamic.
        assertEquals(expected.editableFiles().count(), actual.editableFiles().count(), "Editable files count mismatch");
        for (int i = 0; i < expected.editableFiles().toList().size(); i++) {
            assertContextFragmentsEqual(expected.editableFiles().toList().get(i), actual.editableFiles().toList().get(i));
        }

        assertEquals(expected.readonlyFiles().count(), actual.readonlyFiles().count(), "Readonly files count mismatch");
         for (int i = 0; i < expected.readonlyFiles().toList().size(); i++) {
            assertContextFragmentsEqual(expected.readonlyFiles().toList().get(i), actual.readonlyFiles().toList().get(i));
        }
        
        // Compare virtual fragments (this is where frozen dynamic path fragments will also end up)
        var expectedVirtuals = expected.virtualFragments().sorted(java.util.Comparator.comparingInt(ContextFragment::id)).toList();
        var actualVirtuals = actual.virtualFragments().sorted(java.util.Comparator.comparingInt(ContextFragment::id)).toList();
        assertEquals(expectedVirtuals.size(), actualVirtuals.size(), "Virtual fragments count mismatch");
        for (int i = 0; i < expectedVirtuals.size(); i++) {
            assertContextFragmentsEqual(expectedVirtuals.get(i), actualVirtuals.get(i));
        }
        
        // Compare task history
        assertEquals(expected.getTaskHistory().size(), actual.getTaskHistory().size(), "Task history size mismatch");
        for (int i = 0; i < expected.getTaskHistory().size(); i++) {
            assertTaskEntriesEqual(expected.getTaskHistory().get(i), actual.getTaskHistory().get(i));
        }
    }

    private void assertContextFragmentsEqual(ContextFragment expected, ContextFragment actual) throws IOException, InterruptedException {
        assertEquals(expected.id(), actual.id(), "Fragment ID mismatch");
        assertEquals(expected.getType(), actual.getType(), "Fragment type mismatch for ID " + expected.id());
        assertEquals(expected.description(), actual.description(), "Fragment description mismatch for ID " + expected.id());
        assertEquals(expected.isText(), actual.isText(), "Fragment isText mismatch for ID " + expected.id());
        assertEquals(expected.syntaxStyle(), actual.syntaxStyle(), "Fragment syntaxStyle mismatch for ID " + expected.id());

        if (expected.isText()) {
            assertEquals(expected.text(), actual.text(), "Fragment text content mismatch for ID " + expected.id());
        } else {
            // For image fragments, compare byte content if both are FrozenFragment or can provide bytes
            if (expected instanceof FrozenFragment expectedFf && actual instanceof FrozenFragment actualFf) {
                assertArrayEquals(expectedFf.imageBytesContent(), actualFf.imageBytesContent(), "FrozenFragment imageBytesContent mismatch for ID " + expected.id());
            } else if (expected.image() != null && actual.image() != null) { // Fallback for non-frozen, if any after freezing
                assertArrayEquals(imageToBytes(expected.image()), imageToBytes(actual.image()), "Fragment image content mismatch for ID " + expected.id());
            }
        }

        // Compare sources and files (ProjectFile and CodeUnit DTOs are by value)
        assertEquals(expected.sources().stream().map(CodeUnit::fqName).collect(Collectors.toSet()),
                     actual.sources().stream().map(CodeUnit::fqName).collect(Collectors.toSet()),
                     "Fragment sources mismatch for ID " + expected.id());
        assertEquals(expected.files().stream().map(ProjectFile::toString).collect(Collectors.toSet()),
                     actual.files().stream().map(ProjectFile::toString).collect(Collectors.toSet()),
                     "Fragment files mismatch for ID " + expected.id());

        if (expected instanceof FrozenFragment expectedFf && actual instanceof FrozenFragment actualFf) {
            assertEquals(expectedFf.originalClassName(), actualFf.originalClassName(), "FrozenFragment originalClassName mismatch for ID " + expected.id());
            assertEquals(expectedFf.meta(), actualFf.meta(), "FrozenFragment meta mismatch for ID " + expected.id());
        }
    }
    
    private void assertTaskEntriesEqual(TaskEntry expected, TaskEntry actual) {
        assertEquals(expected.sequence(), actual.sequence());
        assertEquals(expected.isCompressed(), actual.isCompressed());
        if (expected.isCompressed()) {
            assertEquals(expected.summary(), actual.summary());
        } else {
            assertNotNull(expected.log());
            assertNotNull(actual.log());
            assertEquals(expected.log().description(), actual.log().description());
            assertEquals(expected.log().messages().size(), actual.log().messages().size());
            for (int i = 0; i < expected.log().messages().size(); i++) {
                ChatMessage expectedMsg = expected.log().messages().get(i);
                ChatMessage actualMsg = actual.log().messages().get(i);
                assertEquals(expectedMsg.type(), actualMsg.type());
                assertEquals(Messages.getRepr(expectedMsg), Messages.getRepr(actualMsg));
            }
        }
    }

    @Test
    void testFragmentIdContinuityAfterLoad() throws IOException {
        var history = new ContextHistory();
        var projectFile = new ProjectFile(tempDir, "dummy.txt");
        Files.writeString(projectFile.absPath(), "content");
        var ctxFragment = new ContextFragment.ProjectPathFragment(projectFile, mockContextManager); // ID 1
        var strFragment = new ContextFragment.StringFragment(mockContextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE); // ID 2
        
        var context = new Context(mockContextManager, "Initial")
            .addEditableFiles(List.of(ctxFragment))
            .addVirtualFragment(strFragment);
        history.setInitialContext(context);

        Path zipFile = tempDir.resolve("id_continuity_history.zip");
        HistoryIo.writeZip(history, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager); // Deserialization updates ContextFragment.nextId

        int maxIdFromLoadedFragments = loadedHistory.getHistory().stream()
            .flatMap(Context::allFragments)
            .mapToInt(ContextFragment::id)
            .max().orElse(0);

        // CurrentNextId should be maxId + 1
        int currentNextId = ContextFragment.getCurrentMaxId();
        assertTrue(currentNextId > maxIdFromLoadedFragments, 
                   "ContextFragment.nextId should be greater than the max ID found in loaded fragments.");
        
        // Create a new fragment; it should get `currentNextId`
        var newFragment = new ContextFragment.StringFragment(mockContextManager, "new", "new desc", SyntaxConstants.SYNTAX_STYLE_NONE);
        assertEquals(currentNextId, newFragment.id(), "New fragment should get the expected next ID.");
        assertEquals(currentNextId + 1, ContextFragment.getCurrentMaxId(), "ContextFragment.nextId should increment after new fragment creation.");
    }

    // --- Kept Tests for FrozenFragment Interning ---
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
        ProjectFile pf = new ProjectFile(tempDir, "fileA.txt");

        var liveFragment1 = new ContextFragment.ProjectPathFragment(pf, mockContextManager);
        // Create a new ProjectPathFragment instance for the same file, to simulate different live fragments pointing to same content
        var liveFragment2 = new ContextFragment.ProjectPathFragment(pf, mockContextManager);

        FrozenFragment frozen1 = FrozenFragment.freeze(liveFragment1, mockContextManager);
        FrozenFragment frozen2 = FrozenFragment.freeze(liveFragment2, mockContextManager);

        assertSame(frozen1, frozen2, "Frozen ProjectPathFragments with identical file content should be the same instance.");
        assertEquals(liveFragment1.id(), frozen1.id(), "ID of interned fragment should be from the first live fragment.");
        assertEquals(frozen1.getContentHash(), frozen2.getContentHash(), "Content hashes should be identical.");

        // Modify content and check new frozen fragment
        Files.writeString(fileAPath, "Modified content");
        var liveFragment3 = new ContextFragment.ProjectPathFragment(pf, mockContextManager);
        FrozenFragment frozen3 = FrozenFragment.freeze(liveFragment3, mockContextManager);

        assertNotSame(frozen1, frozen3, "FrozenFragment should be different after content modification.");
        assertNotEquals(frozen1.getContentHash(), frozen3.getContentHash(), "Content hashes should differ after content modification.");
    }

    @Test
    void testFrozenFragmentInterning_PasteImageFragment_SameImage() throws Exception {
        BufferedImage image1Data = createTestImage(Color.RED, 1, 1);
        BufferedImage image2Data = createTestImage(Color.RED, 1, 1); // Same content

        var liveFragment1 = new ContextFragment.PasteImageFragment(mockContextManager, image1Data, CompletableFuture.completedFuture("Image Description"));
        var liveFragment2 = new ContextFragment.PasteImageFragment(mockContextManager, image2Data, CompletableFuture.completedFuture("Image Description"));

        FrozenFragment frozen1 = FrozenFragment.freeze(liveFragment1, mockContextManager);
        FrozenFragment frozen2 = FrozenFragment.freeze(liveFragment2, mockContextManager);

        assertSame(frozen1, frozen2, "Frozen PasteImageFragments with identical image data should be the same instance.");
        assertEquals(liveFragment1.id(), frozen1.id(), "ID of interned fragment should be from the first live fragment.");
        assertEquals(frozen1.getContentHash(), frozen2.getContentHash(), "Content hashes should be identical.");

        // Different image
        BufferedImage image3Data = createTestImage(Color.BLUE, 1, 1);
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
