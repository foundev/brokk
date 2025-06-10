package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.testutil.TestProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests demonstrating that the enhanced JavaAnalyzer is more lenient
 * toward decompiled files with synthetic names and provides better
 * semantic information in skeleton generation.
 */
public class JavaAnalyzerLeniencyTest {

    private TestProject testProject;
    private JavaAnalyzer analyzer;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        this.tempDir = tempDir;
        testProject = new TestProject(tempDir, Language.JAVA);
        
        // Create some test files with synthetic names
        createSyntheticClassFile();
        createSyntheticFieldFile();
        
        // Initialize analyzer with the test project path
        analyzer = new JavaAnalyzer(testProject.getRoot());
    }

    @Test
    void testSyntheticClassNameImprovement() {
        // Test that field names get conservative improvement (underscore removal)
        String fieldImproved = callImproveSyntheticName("field_47");
        assertEquals("field47", fieldImproved, "field_47 should have underscore removed");
        
        // Test that class names are left unchanged (no JGit-specific logic)
        String classUnchanged = callImproveSyntheticName("class_30");
        assertEquals("class_30", classUnchanged, "class_30 should be unchanged");
        
        // Test non-synthetic names are never changed
        String regularName = callImproveSyntheticName("RegularClass");
        assertEquals("RegularClass", regularName, "Regular class names should be unchanged");
    }

    @Test
    void testSyntheticFieldNameImprovement() {
        // Test that field_47 gets improved to field47
        String improved = callImproveSyntheticName("field_47");
        assertEquals("field47", improved, "field_47 should be improved to field47");
        
        // Test other field patterns
        assertEquals("field104", callImproveSyntheticName("field_104"));
        assertEquals("field96", callImproveSyntheticName("field_96"));
    }

    @Test
    void testTypeNameSanitization() {
        // Test that regular types work correctly (most important)
        String regularSanitized = callSanitizeType("java.lang.String");
        assertEquals("String", regularSanitized, "Regular type names should work correctly");
        
        // Test that synthetic class names are left unchanged
        String syntheticSanitized = callSanitizeType("org.eclipse.jgit.util.class_30");
        assertEquals("class_30", syntheticSanitized, "Synthetic class names unchanged");
        
        // Test with generic types
        String genericSanitized = callSanitizeType("java.util.List<java.lang.String>");
        assertEquals("List<String>", genericSanitized, "Generic types should work correctly");
        
        // Test with arrays
        String arraySanitized = callSanitizeType("java.lang.String[]");
        assertEquals("String[]", arraySanitized, "Array types should work correctly");
    }

    @Test 
    void testFilteringLeniency() {
        // Test the conservative filtering approach
        
        // Method filtering - only exclude obviously problematic methods
        assertFalse(callIsUnwantedMethod("regularMethod"), "Regular methods should not be filtered");
        assertTrue(callIsUnwantedMethod("<lambda>0"), "Lambda methods should be filtered");
        assertFalse(callIsUnwantedMethod("access$000"), "Access methods preserved with conservative filtering");
        assertTrue(callIsUnwantedMethod("$assertionsDisabled"), "Assertion fields should be filtered");
        
        // Field filtering - very conservative
        assertFalse(callIsUnwantedField("field_47"), "Decompiled fields should NOT be filtered");
        assertTrue(callIsUnwantedField("this$0"), "Synthetic outer class refs should be filtered");
        assertTrue(callIsUnwantedField("$assertionsDisabled"), "Assertion fields should be filtered");
        assertFalse(callIsUnwantedField("val$captured"), "Captured variables preserved with conservative filtering");
        
        // Nested class filtering - only filter very low numbers and obvious synthetics
        assertFalse(callIsUnwantedNestedClass("class_30"), "Meaningful synthetic classes should NOT be filtered");
        assertTrue(callIsUnwantedNestedClass("class_0"), "Very low-numbered synthetic classes should be filtered");
        assertFalse(callIsUnwantedNestedClass("class_10"), "Higher-numbered classes preserved with conservative filtering");
        assertTrue(callIsUnwantedNestedClass("<lambda>1"), "Lambda classes should be filtered");
        assertTrue(callIsUnwantedNestedClass("Runnable$0"), "Anonymous classes with $number should be filtered");
    }

    @Test
    void testSkeletonGeneration() throws IOException {
        // For now, just test that the analyzer can be created without errors
        // Full skeleton generation testing would require setting up a complete CPG
        // which is complex in a unit test environment
        assertNotNull(analyzer, "Analyzer should be created successfully");
        
        // Test that we can call the sanitizeType method which is used in skeleton generation
        String improvedType = callSanitizeType("java.lang.String");
        assertEquals("String", improvedType, "Type sanitization should work correctly for regular types");
        
        // Test filtering methods that are used during skeleton generation
        assertFalse(callIsUnwantedField("field_47"), "Synthetic fields should not be filtered out");
        assertTrue(callIsUnwantedField("$assertionsDisabled"), "Assertion fields should be filtered out");
        
        // These improvements will be applied when actual skeleton generation occurs
        // in the real application with properly decompiled JGit files
    }

    private void createSyntheticClassFile() throws IOException {
        String syntheticClass = """
            package test.synthetic;
            
            public class class_30 {
                public static final class_30 DETECTED = new class_30();
                
                public boolean exists(java.io.File file) {
                    return file.exists();
                }
            }
            """;
        
        Path syntheticFile = tempDir.resolve("class_30.java");
        Files.writeString(syntheticFile, syntheticClass, StandardCharsets.UTF_8);
    }
    
    private void createSyntheticFieldFile() throws IOException {
        String syntheticFields = """
            package test.synthetic;
            
            public class SyntheticFields {
                private class_30 field_47;
                private java.io.RandomAccessFile field_104;
                private static final int field_96 = 1048576;
                
                public SyntheticFields(class_30 fs) {
                    this.field_47 = fs;
                }
            }
            """;
        
        Path fieldsFile = tempDir.resolve("SyntheticFields.java");
        Files.writeString(fieldsFile, syntheticFields, StandardCharsets.UTF_8);
    }

    // Helper methods to access private methods for testing
    private String callImproveSyntheticName(String name) {
        try {
            var method = JavaAnalyzer.class.getDeclaredMethod("improveSyntheticName", String.class);
            method.setAccessible(true);
            return (String) method.invoke(analyzer, name);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call improveSyntheticName", e);
        }
    }
    
    private String callSanitizeType(String type) {
        try {
            var method = JavaAnalyzer.class.getDeclaredMethod("sanitizeType", String.class);
            method.setAccessible(true);
            return (String) method.invoke(analyzer, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call sanitizeType", e);
        }
    }
    
    private boolean callIsUnwantedMethod(String methodName) {
        try {
            var method = JavaAnalyzer.class.getDeclaredMethod("isUnwantedMethod", String.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(analyzer, methodName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call isUnwantedMethod", e);
        }
    }
    
    private boolean callIsUnwantedField(String fieldName) {
        try {
            var method = JavaAnalyzer.class.getDeclaredMethod("isUnwantedField", String.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(analyzer, fieldName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call isUnwantedField", e);
        }
    }
    
    private boolean callIsUnwantedNestedClass(String className) {
        try {
            var method = JavaAnalyzer.class.getDeclaredMethod("isUnwantedNestedClass", String.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(analyzer, className);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call isUnwantedNestedClass", e);
        }
    }
}