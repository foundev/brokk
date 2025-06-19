package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class FragmentUtils {

    private FragmentUtils() {
        // Private constructor to prevent instantiation
    }

    private static void updateDigest(MessageDigest md, String data) {
        md.update(data.getBytes(StandardCharsets.UTF_8));
    }

    private static void updateDigest(MessageDigest md, byte[] data) {
        md.update(data);
    }

    private static void updateDigest(MessageDigest md, boolean data) {
        md.update((byte) (data ? 1 : 0));
    }

    private static String calculateHashInternal(ContextFragment.FragmentType type,
                                              String description,
                                              @Nullable String shortDescription,
                                              @Nullable String textContent,
                                              @Nullable byte[] imageBytesContent,
                                              boolean isTextFragment,
                                              String syntaxStyle,
                                              @Nullable Set<ProjectFile> files,
                                               String originalClassName,
                                               @Nullable Map<String, String> meta)
    {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            updateDigest(md, type.name());
            updateDigest(md, description);
            if (shortDescription != null) { // Only include if provided
                updateDigest(md, shortDescription);
            }
            if (textContent != null) {
                updateDigest(md, textContent);
            }
            if (imageBytesContent != null) {
                updateDigest(md, imageBytesContent);
            }
            updateDigest(md, isTextFragment);
            updateDigest(md, syntaxStyle);

            // Per the intern's changes, files and meta can be null or empty so let's
            // standardize on treating null and empty the same: ignore them.
            // Also, there is no need for `files.stream().filter(Objects::nonNull)`
            // since NullAway should ensure that the set does not contain nulls.
            if (files != null && !files.isEmpty()) {
                String sortedFilesString = files.stream()
                                                .map(pf -> pf.getRoot().toString() + "|" + pf.getRelPath().toString())
                                                .sorted()
                                                .collect(Collectors.joining(";"));
                updateDigest(md, sortedFilesString);
            }

            updateDigest(md, originalClassName);

            if (meta != null && !meta.isEmpty()) {
                String sortedMetaString = meta.entrySet().stream()
                                              .sorted(Map.Entry.comparingByKey())
                                              .map(entry -> entry.getKey() + "=" + entry.getValue())
                                              .collect(Collectors.joining(";"));
                updateDigest(md, sortedMetaString);
            }

            byte[] digest = md.digest();
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /**
     * Calculates a content hash for fragments primarily defined by text.
     */
    public static String calculateContentHash(ContextFragment.FragmentType type,
                                                String description,
                                                String textContent,
                                                String syntaxStyle,
                                                String originalClassName) {
        return calculateHashInternal(type, description, null, textContent, null, true, syntaxStyle, null, originalClassName, null);
    }

    /**
     * Calculates a content hash for fragments that might include images and metadata, but not a separate shortDescription.
     * Used by AnonymousImageFragment.
     */
    public static String calculateContentHash(ContextFragment.FragmentType type,
                                              String description,
                                              @Nullable String textContent, // null for images
                                              @Nullable byte[] imageBytesContent,
                                              boolean isTextFragment,
                                              String syntaxStyle,
                                              @Nullable Set<ProjectFile> files,
                                              @Nullable String originalClassName,
                                              @Nullable Map<String, String> meta)
    {
        return calculateHashInternal(type, description, null, textContent, imageBytesContent, isTextFragment, syntaxStyle, files, originalClassName, meta);
    }

    /**
     * Comprehensive content hash calculation, typically used by FrozenFragment.
     * Includes a distinct shortDescription.
     */
    public static String calculateContentHash(ContextFragment.FragmentType type,
                                              String description,
                                              String shortDescription,
                                              @Nullable String textContent,
                                              @Nullable byte[] imageBytesContent,
                                              boolean isTextFragment,
                                              String syntaxStyle,
                                              @Nullable Set<ProjectFile> files,
                                              @Nullable String originalClassName,
                                              @Nullable Map<String, String> meta) {
        return calculateHashInternal(type, description, shortDescription, textContent, imageBytesContent, isTextFragment, syntaxStyle, files, originalClassName, meta);
    }
}
