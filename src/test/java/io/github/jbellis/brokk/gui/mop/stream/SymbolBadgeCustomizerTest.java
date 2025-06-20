package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.testutil.TestProject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple sanity-checks for {@link SymbolBadgeCustomizer}.
 */
public class SymbolBadgeCustomizerTest {

    /**
     * Mock ContextManager that provides a ready analyzer for testing
     */
    private static class MockContextManager implements IContextManager {
        private final MockAnalyzerWrapper analyzerWrapper = new MockAnalyzerWrapper();

        @Override
        public AnalyzerWrapper getAnalyzerWrapper() {
            return analyzerWrapper;
        }
    }

    /**
     * Mock AnalyzerWrapper that simulates a ready state
     */
    private static class MockAnalyzerWrapper extends AnalyzerWrapper {
        private final MockAnalyzer analyzer = new MockAnalyzer();

        public MockAnalyzerWrapper() {
            super(createTestProject(), createTaskRunner(), null);
        }
        
        private static TestProject createTestProject() {
            return new TestProject(Path.of("/tmp/test"), 
                                 io.github.jbellis.brokk.analyzer.Language.JAVA);
        }
        
        private static io.github.jbellis.brokk.ContextManager.TaskRunner createTaskRunner() {
            return new io.github.jbellis.brokk.ContextManager.TaskRunner() {
                @Override
                public <T> java.util.concurrent.Future<T> submit(String taskDescription, java.util.concurrent.Callable<T> task) {
                    // Return a completed future for testing
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
            };
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public IAnalyzer getNonBlocking() {
            return analyzer;
        }
    }

    /**
     * Mock analyzer that returns test symbols
     */
    private static class MockAnalyzer implements IAnalyzer {
        @Override
        public Optional<CodeUnit> getDefinition(String fqName) {
            // Return a mock symbol for common test patterns
            if (fqName.matches(".*[A-Z].*")) { // Contains uppercase, likely a symbol
                return Optional.of(CodeUnit.cls(new ProjectFile(Path.of("/tmp/test"), "mock.java"),
                                               "", fqName));
            }
            return Optional.empty();
        }
    }

    private Element apply(String html) {
        Document doc = Jsoup.parse(html);
        Element body  = doc.body();
        SymbolBadgeCustomizer.create(new MockContextManager()).customize(body);
        return body;
    }

    @Test
    public void testAnchorWithDataSymbolIdGetsBadge() {
        var body = apply("<p><a href=\"#\" data-symbol-id=\"Foo\">Foo</a></p>");
        assertEquals(1, body.select("a[data-symbol-id] > span.badge-symbol").size(),
                     "Anchor with data-symbol-id should receive a badge");
    }

    @Test
    public void testAnchorWithSymbolTextGetsAttributeAndBadge() {
        var body = apply("<p><a href=\"#\">Foo.Bar</a></p>");
        var anchor = body.selectFirst("a");
        assertNotNull(anchor.attr("data-symbol-id"), "SymbolId attribute should be added");
        assertFalse(anchor.attr("data-symbol-id").isBlank());
        assertEquals(1, anchor.select("span.badge-symbol").size(),
                     "Anchor with symbol text should get a badge");
    }

    @Test
    public void testInlineCodeSymbolGetsBadge() {
        var body = apply("<p>Call <code>Foo.bar()</code> now</p>");
        assertEquals(1, body.select("code + span.badge-symbol").size(),
                     "Inline code symbol should be followed by a badge");
    }
    
    @Test
    public void testFactoryReturnsDefaultWhenAnalyzerWrapperIsNull() {
        // Create a mock context manager that returns null for analyzer wrapper
        var mockContextManager = new IContextManager() {
            @Override
            public AnalyzerWrapper getAnalyzerWrapper() {
                return null; // Simulate uninitialized state
            }
        };
        
        var customizer = SymbolBadgeCustomizer.create(mockContextManager);
        assertEquals(HtmlCustomizer.DEFAULT, customizer, 
                     "Should return DEFAULT customizer when analyzer wrapper is null");
    }
    
    @Test
    public void testFactoryReturnsDefaultWhenContextManagerIsNull() {
        var customizer = SymbolBadgeCustomizer.create(null);
        assertEquals(HtmlCustomizer.DEFAULT, customizer, 
                     "Should return DEFAULT customizer when context manager is null");
    }
}
