package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.testutil.TestContextManager;
import io.github.jbellis.brokk.TestConsoleIO;
import io.github.jbellis.brokk.testutil.TestProject;
import io.github.jbellis.brokk.util.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CodeAgentTest {

    @TempDir
    Path projectRoot;

    TestContextManager contextManager;
    io.github.jbellis.brokk.TestConsoleIO consoleIO;
    CodeAgent codeAgent;
    EditBlockParser parser;
    BiFunction<String, Path, Environment.ShellCommandRunner> originalShellCommandRunnerFactory;


    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(projectRoot);
        contextManager = new TestContextManager(projectRoot);
        consoleIO = new io.github.jbellis.brokk.TestConsoleIO();
        // For tests not needing LLM, model can be null or a dummy,
        // as CodeAgent's constructor doesn't use it directly.
        // Llm instance creation is deferred to runTask/runQuickTask.
        codeAgent = new CodeAgent(contextManager, null, consoleIO);
        parser = EditBlockParser.getParserFor(""); // Basic parser

        // Save original shell command runner factory
        originalShellCommandRunnerFactory = Environment.shellCommandRunnerFactory;
    }

    @AfterEach
    void tearDown() {
        // Restore original shell command runner factory
        Environment.shellCommandRunnerFactory = originalShellCommandRunnerFactory;
    }

    private CodeAgent.LoopContext createLoopContext(String goal,
                                                    List<ChatMessage> taskMessages,
                                                    UserMessage nextRequest,
                                                    List<EditBlock.SearchReplaceBlock> pendingBlocks,
                                                    int consecutiveParseFailures,
                                                    int consecutiveApplyFailures,
                                                    int blocksAppliedWithoutBuild,
                                                    String lastBuildError) {
        var conversationState = new CodeAgent.ConversationState(
                new ArrayList<>(taskMessages), // Modifiable copy
                nextRequest,
                List.of() // originalWorkspaceEditableMessages - empty for these tests
        );
        var workspaceState = new CodeAgent.WorkspaceState(
                new ArrayList<>(pendingBlocks), // Modifiable copy
                consecutiveParseFailures,
                consecutiveApplyFailures,
                blocksAppliedWithoutBuild,
                lastBuildError,
                new HashSet<>(), // changedFiles
                new HashMap<>() // originalFileContents
        );
        return new CodeAgent.LoopContext(conversationState, workspaceState, goal);
    }

    private CodeAgent.LoopContext createBasicLoopContext(String goal) {
        return createLoopContext(goal, List.of(), new UserMessage("test request"), List.of(), 0, 0, 0, "");
    }

    // P-1: parsePhase – pure parse error
    @Test
    void testParsePhase_pureParseError() {
        var loopContext = createBasicLoopContext("test goal");
        String llmTextWithParseError = "This is not a valid edit block structure.";

        var result = codeAgent.parsePhase(loopContext, llmTextWithParseError, false, parser);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertEquals(1, retryStep.loopContext().workspaceState().consecutiveParseFailures());
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest()).contains("Failed to parse edit blocks"));
    }

    @Test
    void testParsePhase_pureParseError_reachesMaxAttempts() {
        var loopContext = createLoopContext("test goal", List.of(), new UserMessage("initial"), List.of(), CodeAgent.MAX_PARSE_ATTEMPTS_FOR_TEST, 0,0, "");
        String llmTextWithParseError = "This is not a valid edit block structure.";

        var result = codeAgent.parsePhase(loopContext, llmTextWithParseError, false, parser);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var fatalStep = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.PARSE_ERROR, fatalStep.stopDetails().reason());
        assertTrue(fatalStep.stopDetails().explanation().contains("Parse error limit reached"));
    }

    // P-2: parsePhase – partial parse + error
    @Test
    void testParsePhase_partialParseWithError() {
        var loopContext = createBasicLoopContext("test goal");
        String llmText = """
                         ```java
                         file.java
                         <<<<<<< SEARCH
                         System.out.println("Hello");
                         =======
                         System.out.println("World");
                         >>>>>>> REPLACE
                         ```
                         This part is a parse error.
                         """;

        var result = codeAgent.parsePhase(loopContext, llmText, false, parser);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertEquals(0, retryStep.loopContext().workspaceState().consecutiveParseFailures(), "Parse failures should reset on partial success");
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest()).contains("continue from there"));
        assertEquals(1, retryStep.loopContext().workspaceState().pendingBlocks().size(), "One block should be parsed and pending");
    }

    // P-3a: parsePhase – isPartial flag handling (with zero blocks)
    @Test
    void testParsePhase_isPartial_zeroBlocks() {
        var loopContext = createBasicLoopContext("test goal");
        String llmTextNoBlocks = "Thinking...";

        var result = codeAgent.parsePhase(loopContext, llmTextNoBlocks, true, parser); // isPartial = true

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest()).contains("cut off before you provided any code blocks"));
        assertTrue(retryStep.loopContext().workspaceState().pendingBlocks().isEmpty());
    }

    // P-3b: parsePhase – isPartial flag handling (with >=1 block)
    @Test
    void testParsePhase_isPartial_withBlocks() {
        var loopContext = createBasicLoopContext("test goal");
        String llmTextWithBlock = """
                                  ```java
                                  file.java
                                  <<<<<<< SEARCH
                                  System.out.println("Hello");
                                  =======
                                  System.out.println("World");
                                  >>>>>>> REPLACE
                                  ```
                                  """;

        var result = codeAgent.parsePhase(loopContext, llmTextWithBlock, true, parser); // isPartial = true

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest()).contains("continue from there"));
        assertEquals(1, retryStep.loopContext().workspaceState().pendingBlocks().size());
    }
    
    // P-4: parsePhase – AI-message redaction
    @Test
    void testParsePhase_aiMessageRedaction() {
        var taskMessages = new ArrayList<ChatMessage>();
        var originalAiMessage = new AiMessage("""
                                           ```java
                                           file.java
                                           <<<<<<< SEARCH
                                           Hello
                                           =======
                                           World
                                           >>>>>>> REPLACE
                                           ```
                                           """);
        taskMessages.add(originalAiMessage);
        var loopContext = createLoopContext("test goal", taskMessages, new UserMessage("next"), List.of(),0,0,0,"");
        String llmTextNewBlock = """
                                 ```java
                                 another.java
                                 <<<<<<< SEARCH
                                 Foo
                                 =======
                                 Bar
                                 >>>>>>> REPLACE
                                 ```
                                 """;

        var result = codeAgent.parsePhase(loopContext, llmTextNewBlock, false, parser);

        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;

        var finalTaskMessages = continueStep.loopContext().conversationState().taskMessages();
        assertEquals(1, finalTaskMessages.size(), "Should still have one AI message after redaction");
        assertInstanceOf(AiMessage.class, finalTaskMessages.get(0));
        var redactedAiMessage = (AiMessage) finalTaskMessages.get(0);
        assertFalse(redactedAiMessage.text().contains("<<<<<<< SEARCH"), "Redacted message should not contain SEARCH block markers");
        assertTrue(redactedAiMessage.text().contains("file.java"), "Redacted message should still mention the file");
        assertEquals(1, continueStep.loopContext().workspaceState().pendingBlocks().size(), "New block should be pending");
    }

    // A-1: applyPhase – read-only conflict
    @Test
    void testApplyPhase_readOnlyConflict() {
        var readOnlyFile = contextManager.toFile("readonly.txt");
        contextManager.addReadonlyFile(readOnlyFile);

        var block = new EditBlock.SearchReplaceBlock(readOnlyFile.toString(), "search", "replace");
        var loopContext = createLoopContext("test goal", List.of(), new UserMessage("req"), List.of(block), 0,0,0, "");

        var result = codeAgent.applyPhase(loopContext, parser);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var fatalStep = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.READ_ONLY_EDIT, fatalStep.stopDetails().reason());
        assertTrue(fatalStep.stopDetails().explanation().contains(readOnlyFile.toString()));
    }

    // A-2: applyPhase – total apply failure (below fallback threshold)
    @Test
    void testApplyPhase_totalApplyFailure_belowThreshold() throws IOException {
        var file = contextManager.toFile("test.txt");
        file.write("initial content");
        contextManager.addEditableFile(file);

        var nonMatchingBlock = new EditBlock.SearchReplaceBlock(file.toString(), "text that does not exist", "replacement");
        var loopContext = createLoopContext("test goal", List.of(), new UserMessage("req"), List.of(nonMatchingBlock), 0,0,0, "");

        var result = codeAgent.applyPhase(loopContext, parser);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertEquals(1, retryStep.loopContext().workspaceState().consecutiveApplyFailures());
        assertEquals(0, retryStep.loopContext().workspaceState().blocksAppliedWithoutBuild());
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest()).contains("Failed to apply the following edit block"));
    }

    // A-4: applyPhase – mix success & failure
    @Test
    void testApplyPhase_mixSuccessAndFailure() throws IOException {
        var file1 = contextManager.toFile("file1.txt");
        file1.write("hello world");
        contextManager.addEditableFile(file1);

        var file2 = contextManager.toFile("file2.txt");
        file2.write("foo bar");
        contextManager.addEditableFile(file2);

        var successBlock = new EditBlock.SearchReplaceBlock(file1.toString(), "hello", "goodbye");
        var failureBlock = new EditBlock.SearchReplaceBlock(file2.toString(), "nonexistent", "text");

        var loopContext = createLoopContext("test goal", List.of(), new UserMessage("req"), List.of(successBlock, failureBlock), 0,0,0, "");
        var result = codeAgent.applyPhase(loopContext, parser);

        assertInstanceOf(CodeAgent.Step.Retry.class, result); // Retries because one block failed
        var retryStep = (CodeAgent.Step.Retry) result;
        assertEquals(0, retryStep.loopContext().workspaceState().consecutiveApplyFailures(), "Consecutive failures should reset on partial success");
        assertEquals(1, retryStep.loopContext().workspaceState().blocksAppliedWithoutBuild(), "One block should have been applied");
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest()).contains("Succeeded with 1 edit(s), but failed to apply the following"));

        assertEquals("goodbye world", file1.read()); // Verify successful edit
    }
    
    // V-1: verifyPhase – skip when no edits
    @Test
    void testVerifyPhase_skipWhenNoEdits() {
        var loopContext = createBasicLoopContext("test goal"); // blocksAppliedWithoutBuild is 0 by default
        var result = codeAgent.verifyPhase(loopContext);

        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        // Ensure state is unchanged essentially
        assertSame(loopContext, continueStep.loopContext());
    }

    // V-2: verifyPhase – verification command absent
    @Test
    void testVerifyPhase_verificationCommandAbsent() {
        ((TestProject) contextManager.getProject()).setBuildDetails(BuildAgent.BuildDetails.EMPTY); // No commands
        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(),0,0,1, ""); // 1 block applied

        var result = codeAgent.verifyPhase(loopContext);

        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertEquals("", continueStep.loopContext().workspaceState().lastBuildError());
        assertEquals(0, continueStep.loopContext().workspaceState().blocksAppliedWithoutBuild()); // Reset after (skipped) build
    }

    // V-3: verifyPhase – build failure loop (mocking Environment.runShellCommand)
    @Test
    void testVerifyPhase_buildFailureAndSuccessCycle() {
        ((TestProject) contextManager.getProject()).setBuildDetails(
                new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test {{files}}", Set.of())
        );
        ((TestProject) contextManager.getProject()).setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL); // to use testAllCommand

        java.util.concurrent.atomic.AtomicInteger attempt = new java.util.concurrent.atomic.AtomicInteger(0);
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer) -> {
            int currentAttempt = attempt.getAndIncrement();
            if (currentAttempt == 0) { // First attempt fails
                outputConsumer.accept("Build error line 1");
                throw new Environment.FailureException("Build failed", "Detailed build error output");
            }
            // Second attempt (or subsequent if MAX_BUILD_FAILURES > 1) succeeds
            outputConsumer.accept("Build successful");
            return "Successful output";
        };

        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(), 0,0,1, ""); // 1 block applied

        // First run - build should fail
        var resultFail = codeAgent.verifyPhase(loopContext);
        assertInstanceOf(CodeAgent.Step.Retry.class, resultFail);
        var retryStep = (CodeAgent.Step.Retry) resultFail;
        assertTrue(retryStep.loopContext().workspaceState().lastBuildError().contains("Detailed build error output"));
        assertEquals(0, retryStep.loopContext().workspaceState().blocksAppliedWithoutBuild()); // Reset
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest()).contains("The build failed"));

        // Second run - build should succeed
        var resultSuccess = codeAgent.verifyPhase(retryStep.loopContext());
        assertInstanceOf(CodeAgent.Step.Continue.class, resultSuccess);
        var continueStep = (CodeAgent.Step.Continue) resultSuccess;
        assertEquals("", continueStep.loopContext().workspaceState().lastBuildError());
        assertEquals(0, continueStep.loopContext().workspaceState().blocksAppliedWithoutBuild());
    }

    // INT-1: Interruption during verifyPhase (via Environment stub)
    @Test
    void testVerifyPhase_interruptionDuringBuild() throws InterruptedException {
        ((TestProject) contextManager.getProject()).setBuildDetails(
                new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test {{files}}", Set.of())
        );
        ((TestProject) contextManager.getProject()).setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer) -> {
            throw new InterruptedException("Simulated interruption during shell command");
        };

        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(), 0,0,1, "");

        var result = codeAgent.verifyPhase(loopContext);
        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var fatalStep = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.INTERRUPTED, fatalStep.stopDetails().reason());
    }
}
