package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.gui.Chrome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.zip.ZipFile;

public class Decompiler {
    private static final Logger logger = LogManager.getLogger(Decompiler.class);

    /**
     * Performs the decompilation of the selected JAR file.
     * This method assumes jarPath is a valid JAR file.
     * @param io The Chrome instance for UI feedback
     * @param jarPath Path to the JAR file to decompile.
     * @param runner TaskRunner to run the decompilation task on
     */
    public static void decompileJar(Chrome io, Path jarPath, ContextManager.TaskRunner runner) {
        try {
            String jarName = jarPath.getFileName().toString();
            // Use the *original* project's root to determine the .brokk directory
            Path originalProjectRoot = io.getContextManager().getRoot();
            Path brokkDir = originalProjectRoot.resolve(".brokk");
            Path depsDir = brokkDir.resolve("dependencies");
            Path outputDir = depsDir.resolve(jarName.replaceAll("\\.jar$", "")); // Decompile target dir

            logger.debug("Original project root: {}", originalProjectRoot);
            logger.debug("Decompile output directory: {}", outputDir);

            Files.createDirectories(depsDir);

            // Check if already decompiled
            if (Files.exists(outputDir)) {
                int choice = JOptionPane.showConfirmDialog(
                        io.getFrame(), // Use current IO frame
                        """
                        This JAR appears to have been decompiled previously.
                        Output directory: %s

                        Delete output directory and decompile again?
                        (Choosing 'No' will leave the existing decompiled files unchanged.)
                        """.formatted(outputDir.toString()),
                        "Dependency exists",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );

                if (choice == JOptionPane.YES_OPTION) {
                    logger.debug("Removing old decompiled contents at {}", outputDir);
                    try {
                        Decompiler.deleteDirectoryRecursive(outputDir);
                    } catch (IOException e) {
                        io.toolError("Error deleting existing decompiled directory: " + e.getMessage());
                        return; // Stop if deletion fails
                    }
                    // Recreate the directory after deletion
                    Files.createDirectories(outputDir);
                } else if (choice == JOptionPane.NO_OPTION) {
                    logger.debug("Opening previously decompiled dependency at {}", outputDir);
                    return;
                }
            } else {
                // Create the output directory if it didn't exist
                Files.createDirectories(outputDir);
            }


            io.systemOutput("Decompiling " + jarName + "...");

            // Submit the decompilation task to the provided executor
            runner.submit("Decompiling " + jarName, () -> {
                logger.debug("Starting decompilation in background thread for {}", jarPath);
                Path tempDir = null; // To store the path of the temporary directory

                try {
                    // 1. Create a temporary directory
                    tempDir = Files.createTempDirectory("fernflower-extracted-");
                    logger.debug("Created temporary directory: {}", tempDir);

                    // 2. Extract the JAR contents to the temporary directory
                    Decompiler.extractJarToTemp(jarPath, tempDir);
                    logger.debug("Extracted JAR contents to temporary directory.");

                    // 3. Set up Decompiler with the *final* output directory
                    Map<String, Object> options = Map.of("hes", "1", // hide empty super
                                                         "hdc", "1", // hide default constructor
                                                         "dgs", "1", // decompile generic signature
                                                         "ren", "1" /* rename ambiguous */);
                    ConsoleDecompiler decompiler = new ConsoleDecompiler(
                            outputDir.toFile(), // Use the final desired output directory here
                            options,
                            new org.jetbrains.java.decompiler.main.extern.IFernflowerLogger() {
                                @Override
                                public void writeMessage(String message, Severity severity) {
                                    switch (severity) {
                                        case ERROR -> logger.error("Fernflower: {}", message);
                                        case WARN  -> logger.warn("Fernflower: {}", message);
                                        case INFO  -> logger.info("Fernflower: {}", message);
                                        case TRACE -> logger.trace("Fernflower: {}", message);
                                        default    -> logger.debug("Fernflower: {}", message);
                                    }
                                }

                                @Override
                                public void writeMessage(String message, Severity severity, Throwable t) {
                                    switch (severity) {
                                        case ERROR -> logger.error("Fernflower: {}", message, t);
                                        case WARN  -> logger.warn("Fernflower: {}", message, t);
                                        case INFO  -> logger.info("Fernflower: {}", message, t);
                                        case TRACE -> logger.trace("Fernflower: {}", message, t);
                                        default   -> logger.debug("Fernflower: {}", message, t);
                                    }
                                }
                            }
                    );

                    // 4. Add the *temporary directory* as the source
                    decompiler.addSource(tempDir.toFile());

                    // 5. Decompile
                    logger.info("Starting decompilation process...");
                    decompiler.decompileContext();
                    logger.info("Decompilation process finished.");

                    // Notify user of success
                    io.systemOutput("Decompilation completed. Reopen project to incorporate the new source files.");
                    // Log final directory structure for troubleshooting
                    logger.debug("Final contents of {} after decompilation:", outputDir);
                    try (var pathStream = Files.walk(outputDir, 1)) { // Walk only one level deep for brevity
                        pathStream.forEach(path -> logger.debug("   {}", path.getFileName()));
                    } catch (IOException e) {
                        logger.warn("Error listing output directory contents", e);
                    }
                } catch (Exception e) {
                    // Handle exceptions within the task
                    io.toolError("Error during decompilation process: " + e.getMessage());
                } finally {
                    // 6. Clean up the temporary directory
                    if (tempDir != null) {
                        try {
                            logger.debug("Cleaning up temporary directory: {}", tempDir);
                            Decompiler.deleteDirectoryRecursive(tempDir);
                            logger.debug("Temporary directory deleted.");
                        } catch (IOException e) {
                            logger.error("Failed to delete temporary directory: {}", tempDir, e);
                        }
                    }
                }
                return null;
            });
        } catch (IOException e) {
            // Error *before* starting the worker (e.g., creating directories)
            io.toolError("Error preparing decompilation: " + e.getMessage());
        }
    }

    public static void extractJarToTemp(Path jarPath, Path targetDir) throws IOException {
        // Ensure target directory exists and is a directory
        if (!Files.isDirectory(targetDir)) {
            Files.createDirectories(targetDir); // Create if not exists
        }
        // Use try-with-resources to ensure the ZipFile is closed
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            var entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                // Resolve and normalize, then ensure it's within the target directory
                Path entryPath = targetDir.resolve(entry.getName()).normalize();

                // --- Zip Slip Protection ---
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry is trying to escape the target directory: " + entry.getName());
                }
                // --- End Zip Slip Protection ---

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    // Ensure parent directories exist for the file
                    Files.createDirectories(entryPath.getParent());
                    // Use try-with-resources for the input stream
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        Files.copy(in, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    public static void deleteDirectoryRecursive(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    logger.warn("Failed to delete file: {} ({})", file, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) throws IOException {
                if (exc != null) {
                    logger.warn("Error visiting directory contents for deletion: {} ({})", dir, exc.getMessage());
                    throw exc; // Propagate error
                }
                try {
                    Files.delete(dir);
                } catch (IOException e) {
                    logger.warn("Failed to delete directory: {} ({})", dir, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.warn("Failed to access file for deletion: {} ({})", file, exc.getMessage());
                // Decide if failure should stop the process
                return FileVisitResult.CONTINUE; // Continue deletion attempt
            }
        });
    }
}
