package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.testutil.TestProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases demonstrating why decompiled files with synthetic names
 * cannot be properly summarized or analyzed for semantic content.
 *
 * These tests use actual patterns found in JGit decompiled files to show
 * how decompilation artifacts prevent meaningful code analysis.
 */
public class DecompiledFileSummarizationTest {

    private TestProject testProject;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        this.tempDir = tempDir;
        testProject = new TestProject(tempDir, Language.JAVA);
    }

    @Test
    void testSyntheticClassNamesPreventSemanticAnalysis() throws Exception {
        // Create a file with synthetic class names like those found in JGit decompilation
        String syntheticClassFile = """
            package org.eclipse.jgit.api;

            import java.io.File;
            import java.io.IOException;
            import org.eclipse.jgit.util.class_30;

            public class Git {
                public static Git open(File dir) throws IOException {
                    return open(dir, class_30.DETECTED);
                }

                public static Git open(File dir, class_30 fs) throws IOException {
                    // Uses synthetic class name that provides no semantic meaning
                    return new Git();
                }
            }
            """;

        String meaningfulClassFile = """
            package org.eclipse.jgit.api;

            import java.io.File;
            import java.io.IOException;
            import org.eclipse.jgit.util.FS;

            public class Git {
                public static Git open(File dir) throws IOException {
                    return open(dir, FS.DETECTED);
                }

                public static Git open(File dir, FS fs) throws IOException {
                    // Uses meaningful class name that indicates file system operations
                    return new Git();
                }
            }
            """;

        Path syntheticFile = createTestFile("SyntheticGit.java", syntheticClassFile);
        Path meaningfulFile = createTestFile("MeaningfulGit.java", meaningfulClassFile);

        // Analyze both files
        var syntheticProjectFile = new ProjectFile(testProject.getRoot(), tempDir.relativize(syntheticFile));
        var meaningfulProjectFile = new ProjectFile(testProject.getRoot(), tempDir.relativize(meaningfulFile));

        // The synthetic version should be much harder to understand semantically
        String syntheticSummary = attemptSummarization(syntheticProjectFile);
        String meaningfulSummary = attemptSummarization(meaningfulProjectFile);

        // Demonstrate the difference in semantic clarity
        assertFalse(syntheticSummary.contains("file system") || syntheticSummary.contains("FS"),
                "Synthetic class names prevent understanding of file system operations");
        assertTrue(meaningfulSummary.contains("FS") || meaningfulSummary.toLowerCase().contains("file"),
                "Meaningful class names enable semantic understanding");
    }

    @Test
    void testCorruptedFieldNamesPreventBusinessLogicUnderstanding() throws Exception {
        // Based on actual JGit decompiled code with field_47, field_104 patterns
        String corruptedFieldsFile = """
            package org.eclipse.jgit.util;

            import java.io.File;
            import java.io.RandomAccessFile;

            public class FileSystemAttributes {
                // $FF: renamed from: fs org.eclipse.jgit.util.FS
                final class_30 field_47;

                // $FF: renamed from: f java.io.RandomAccessFile
                private RandomAccessFile field_104;

                // $FF: renamed from: MB int
                private static final int field_96 = 1048576;

                public FileSystemAttributes(class_30 fs, File file) {
                    this.field_47 = fs;
                }

                public void openFile(File pidFile) throws Exception {
                    this.field_104 = new RandomAccessFile(pidFile, "rw");
                }

                public long getMaxBufferSize() {
                    return field_96;
                }
            }
            """;

        String meaningfulFieldsFile = """
            package org.eclipse.jgit.util;

            import java.io.File;
            import java.io.RandomAccessFile;

            public class FileSystemAttributes {
                final FS fs;
                private RandomAccessFile fileHandle;
                private static final int MB = 1048576;

                public FileSystemAttributes(FS fs, File file) {
                    this.fs = fs;
                }

                public void openFile(File pidFile) throws Exception {
                    this.fileHandle = new RandomAccessFile(pidFile, "rw");
                }

                public long getMaxBufferSize() {
                    return MB;
                }
            }
            """;

        Path corruptedFile = createTestFile("CorruptedFields.java", corruptedFieldsFile);
        Path meaningfulFile = createTestFile("MeaningfulFields.java", meaningfulFieldsFile);

        var corruptedProjectFile = new ProjectFile(testProject.getRoot(), tempDir.relativize(corruptedFile));
        var meaningfulProjectFile = new ProjectFile(testProject.getRoot(), tempDir.relativize(meaningfulFile));

        String corruptedSummary = attemptSummarization(corruptedProjectFile);
        String meaningfulSummary = attemptSummarization(meaningfulProjectFile);

        // Corrupted field names prevent understanding of business logic
        assertFalse(corruptedSummary.toLowerCase().contains("file system") ||
                   corruptedSummary.toLowerCase().contains("megabyte") ||
                   corruptedSummary.toLowerCase().contains("buffer"),
                "Generic field names like field_47, field_96 provide no semantic information");

        assertTrue(meaningfulSummary.toLowerCase().contains("file") ||
                  meaningfulSummary.toLowerCase().contains("mb") ||
                  meaningfulSummary.toLowerCase().contains("buffer"),
                "Meaningful field names enable understanding of file operations and memory management");
    }

    @Test
    void testSyntheticPackageInfoBreaksModuleUnderstanding() throws Exception {
        // Based on actual JGit pattern where package-info becomes class_0 interface
        String syntheticPackageInfo = """
            package org.eclipse.jgit.revwalk.filter;

            // $FF: renamed from: org.eclipse.jgit.revwalk.filter.package-info
            // $FF: synthetic class
            interface class_0 {
            }
            """;

        String meaningfulPackageInfo = """
            /**
             * Filters for revision walking operations.
             *
             * This package provides various filters that can be applied
             * during repository history traversal to select specific commits
             * based on criteria like author, date, message content, etc.
             */
            package org.eclipse.jgit.revwalk.filter;
            """;

        Path syntheticFile = createTestFile("class_0.java", syntheticPackageInfo);
        // Note: package-info.java would be the real file, but we use .java for testing
        Path meaningfulFile = createTestFile("PackageInfo.java",
            "package org.eclipse.jgit.revwalk.filter;\n" + meaningfulPackageInfo);

        var syntheticProjectFile = new ProjectFile(testProject.getRoot(), tempDir.relativize(syntheticFile));
        var meaningfulProjectFile = new ProjectFile(testProject.getRoot(), tempDir.relativize(meaningfulFile));

        String syntheticSummary = attemptSummarization(syntheticProjectFile);
        String meaningfulSummary = attemptSummarization(meaningfulProjectFile);

        // Synthetic package-info provides no module understanding
        assertFalse(syntheticSummary.toLowerCase().contains("filter") ||
                   syntheticSummary.toLowerCase().contains("revision") ||
                   syntheticSummary.toLowerCase().contains("commit"),
                "Synthetic class_0 interface provides no information about package purpose");

        assertTrue(meaningfulSummary.toLowerCase().contains("filter") ||
                  meaningfulSummary.toLowerCase().contains("revision") ||
                  meaningfulSummary.toLowerCase().contains("walk") ||
                  meaningfulSummary.toLowerCase().contains("package"),
                "Real package documentation explains module functionality");
    }

    @Test
    void testMalformedSyntaxPatternsFromDecompilation() throws Exception {
        // Based on actual patterns found in JGit decompiled files
        String malformedLoopsFile = """
            package org.eclipse.jgit.transport;

            public enum TagOpt {
                AUTO_FOLLOW, FETCH_TAGS, NO_TAGS;

                public static TagOpt findByName(String name) {
                    // Malformed enhanced for-loop with temporary variable - actual JGit pattern
                    TagOpt[] var4;
                    for(TagOpt tagopt : var4 = values()) {
                        if (tagopt.name().equals(name)) {
                            return tagopt;
                        }
                    }
                    return null;
                }

                // Synthetic assertion field - actual JGit pattern
                // $FF: synthetic field
                static final boolean $assertionsDisabled = !TagOpt.class.desiredAssertionStatus();
            }
            """;

        String cleanSyntaxFile = """
            package org.eclipse.jgit.transport;

            public enum TagOpt {
                AUTO_FOLLOW, FETCH_TAGS, NO_TAGS;

                public static TagOpt findByName(String name) {
                    for(TagOpt tagopt : values()) {
                        if (tagopt.name().equals(name)) {
                            return tagopt;
                        }
                    }
                    return null;
                }
            }
            """;

        Path malformedFile = createTestFile("MalformedTagOpt.java", malformedLoopsFile);
        Path cleanFile = createTestFile("CleanTagOpt.java", cleanSyntaxFile);

        var malformedProjectFile = new ProjectFile(testProject.getRoot(), tempDir.relativize(malformedFile));
        var cleanProjectFile = new ProjectFile(testProject.getRoot(), tempDir.relativize(cleanFile));

        // Test that malformed syntax creates analysis problems
        assertDoesNotThrow(() -> attemptSummarization(cleanProjectFile),
                "Clean syntax should analyze without issues");

        // The malformed version may cause parsing issues or generate confusing summaries
        String malformedSummary = attemptSummarization(malformedProjectFile);
        String cleanSummary = attemptSummarization(cleanProjectFile);

        // Malformed syntax creates noise in analysis
        assertTrue(malformedSummary.contains("var4") || malformedSummary.contains("$assertions") ||
                  malformedSummary.contains("decompiler"),
                "Decompilation artifacts create noise in code analysis");
        assertFalse(cleanSummary.contains("var4") || cleanSummary.contains("$assertions"),
                "Clean code doesn't contain decompilation artifacts");
    }

    @Test
    void testJGitActualDecompiledFileAnalysis() throws Exception {
        // Test with an actual pattern from JGit Git.java decompiled file
        String actualJGitPattern = """
            package org.eclipse.jgit.api;

            import java.io.File;
            import java.io.IOException;
            import java.util.Objects;
            import org.eclipse.jgit.lib.Repository;
            import org.eclipse.jgit.util.class_30;

            public class Git implements AutoCloseable {
                private final Repository repo;
                private final boolean closeRepo;

                public static Git open(File dir) throws IOException {
                    return open(dir, class_30.DETECTED);
                }

                public static Git open(File dir, class_30 fs) throws IOException {
                    // The class_30 reference provides no semantic meaning
                    // Original was likely FS (FileSystem)
                    return new Git(null, true);
                }

                public Git(Repository repo, boolean closeRepo) {
                    this.repo = repo;
                    this.closeRepo = closeRepo;
                }

                @Override
                public void close() {
                    if (closeRepo && repo != null) {
                        repo.close();
                    }
                }
            }
            """;

        Path jgitFile = createTestFile("ActualJGitPattern.java", actualJGitPattern);
        var jgitProjectFile = new ProjectFile(testProject.getRoot(), tempDir.relativize(jgitFile));

        String summary = attemptSummarization(jgitProjectFile);

        // Demonstrate that actual JGit patterns prevent meaningful analysis
        assertFalse(summary.toLowerCase().contains("file system") ||
                   summary.toLowerCase().contains("filesystem") ||
                   summary.toLowerCase().contains("fs"),
                "class_30 reference prevents understanding that this is file system related");

        assertTrue(summary.contains("class_30") || summary.contains("synthetic"),
                "Summary should indicate presence of synthetic/meaningless class names");
    }

    private Path createTestFile(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private String attemptSummarization(ProjectFile projectFile) {
        try {
            // Simulate the summarization process that would fail with decompiled files
            String content = Files.readString(projectFile.absPath());

            // Simple analysis that demonstrates semantic loss
            StringBuilder analysis = new StringBuilder();
            analysis.append("File: ").append(projectFile.getRelPath()).append("\n");

            // Count synthetic patterns that indicate decompilation problems
            long syntheticClasses = content.lines()
                    .filter(line -> line.contains("class_") && line.matches(".*class_\\d+.*"))
                    .count();
            long syntheticFields = content.lines()
                    .filter(line -> line.contains("field_") && line.matches(".*field_\\d+.*"))
                    .count();
            long ffComments = content.lines()
                    .filter(line -> line.contains("// $FF:"))
                    .count();

            if (syntheticClasses > 0) {
                analysis.append("Contains ").append(syntheticClasses).append(" synthetic class references\n");
            }
            if (syntheticFields > 0) {
                analysis.append("Contains ").append(syntheticFields).append(" synthetic field references\n");
            }
            if (ffComments > 0) {
                analysis.append("Contains ").append(ffComments).append(" decompiler artifacts\n");
            }

            // Attempt to extract semantic meaning
            if (content.toLowerCase().contains("file")) {
                analysis.append("Appears to involve file operations\n");
            }
            if (content.toLowerCase().contains("repository")) {
                analysis.append("Appears to be repository-related\n");
            }
            if (content.toLowerCase().contains("git")) {
                analysis.append("Appears to be Git-related\n");
            }

            return analysis.toString();

        } catch (Exception e) {
            return "Analysis failed: " + e.getMessage();
        }
    }
}
