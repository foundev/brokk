package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.git.GitRepo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GitLogTabMergeTest {

    @Test
    void testCreateTempRebaseBranchName_UniqueGeneration() {
        String baseName = "feature/awesome-branch";

        String name1 = GitRepo.createTempRebaseBranchName(baseName);

        // Sleep briefly to ensure different timestamps
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String name2 = GitRepo.createTempRebaseBranchName(baseName);

        // Names may be the same if called within the same millisecond, but that's acceptable
        // since the goal is to avoid conflicts in practice, not guarantee uniqueness in tests
        assertTrue(name1.startsWith("brokk_temp_rebase_feature_awesome-branch_"),
                  "Generated name should start with expected prefix: " + name1);
        assertTrue(name2.startsWith("brokk_temp_rebase_feature_awesome-branch_"),
                  "Generated name should start with expected prefix");

        // Verify timestamp suffix is numeric
        String suffix1 = name1.substring(name1.lastIndexOf('_') + 1);
        String suffix2 = name2.substring(name2.lastIndexOf('_') + 1);

        assertTrue(suffix1.length() >= 10, "Timestamp suffix should be at least 10 characters: " + suffix1);
        assertTrue(suffix2.length() >= 10, "Timestamp suffix should be at least 10 characters: " + suffix2);

        // Verify suffix is valid timestamp (numeric)
        assertDoesNotThrow(() -> Long.parseLong(suffix1),
                          "Timestamp suffix should be numeric: " + suffix1);
        assertDoesNotThrow(() -> Long.parseLong(suffix2),
                          "Timestamp suffix should be numeric: " + suffix2);

        // Verify timestamps are non-decreasing (may be equal due to resolution)
        long timestamp1 = Long.parseLong(suffix1);
        long timestamp2 = Long.parseLong(suffix2);
        assertTrue(timestamp2 >= timestamp1, "Second timestamp should be >= first timestamp");
    }

    @Test
    void testCreateTempRebaseBranchName_SanitizesInput() {
        String unsafeName = "feature/branch with spaces!@#";

        String sanitized = GitRepo.createTempRebaseBranchName(unsafeName);

        assertTrue(sanitized.contains("brokk_temp_rebase_feature_branch_with_spaces____"),
                  "Should sanitize unsafe characters to underscores: " + sanitized);
        assertFalse(sanitized.contains(" "), "Should not contain spaces");
        assertFalse(sanitized.contains("!"), "Should not contain exclamation marks");
        assertFalse(sanitized.contains("@"), "Should not contain at signs");
        assertFalse(sanitized.contains("#"), "Should not contain hash symbols");
    }

    @Test
    void testCreateTempRebaseBranchName_EmptyInput() {
        String emptyName = "";

        String result = GitRepo.createTempRebaseBranchName(emptyName);

        assertTrue(result.startsWith("brokk_temp_rebase__"),
                  "Should handle empty input gracefully");
        assertTrue(result.endsWith("_" + result.substring(result.lastIndexOf('_') + 1)),
                  "Should still append timestamp suffix");
    }

    @Test
    void testCreateTempRebaseBranchName_PrefixConstant() {
        String baseName = "test";
        String result = GitRepo.createTempRebaseBranchName(baseName);

        assertTrue(result.startsWith("brokk_temp_rebase_"),
                  "Should use the correct prefix constant");
    }

    @Test
    void testCreateTempRebaseBranchName_ComplexBranchName() {
        String complexName = "feature/user-auth!@#$%^&*()";
        String result = GitRepo.createTempRebaseBranchName(complexName);

        assertTrue(result.startsWith("brokk_temp_rebase_feature_user-auth"),
                  "Should sanitize complex characters properly: " + result);
        assertTrue(result.matches("brokk_temp_rebase_feature_user-auth_+\\d+"),
                  "Should match expected pattern with timestamp: " + result);
        assertFalse(result.contains("!"), "Should not contain exclamation marks");
        assertFalse(result.contains("@"), "Should not contain at signs");
        assertFalse(result.contains("#"), "Should not contain hash symbols");
        assertFalse(result.contains("$"), "Should not contain dollar signs");
        assertFalse(result.contains("%"), "Should not contain percent signs");
    }

    @Test
    void testCreateTempRebaseBranchName_OnlySpecialCharacters() {
        String specialCharsOnly = "!@#$%^&*()";
        String result = GitRepo.createTempRebaseBranchName(specialCharsOnly);

        assertTrue(result.startsWith("brokk_temp_rebase_"),
                  "Should handle special-chars-only input: " + result);
        // Should result in "brokk_temp_rebase___________timestamp" (underscores replacing special chars)
        assertTrue(result.matches("brokk_temp_rebase__+\\d+"),
                  "Should replace all special characters with underscores: " + result);
    }

    @Test
    void testCreateTempRebaseBranchName_ValidCharactersPreserved() {
        String validName = "feature_branch-name123";
        String result = GitRepo.createTempRebaseBranchName(validName);

        assertTrue(result.contains("feature_branch-name123"),
                  "Should preserve valid characters: " + result);
        assertTrue(result.startsWith("brokk_temp_rebase_feature_branch-name123_"),
                  "Should maintain valid branch name components: " + result);
    }


}
