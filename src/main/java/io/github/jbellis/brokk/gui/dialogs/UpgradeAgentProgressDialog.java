package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.Llm;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.prompts.CodePrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dev.langchain4j.data.message.ChatMessage;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class UpgradeAgentProgressDialog extends JDialog {

    private static final Logger logger = LogManager.getLogger(UpgradeAgentProgressDialog.class);
    private final JProgressBar progressBar;
    private final JTextArea errorTextArea;
    private final JButton cancelButton;
    private final SwingWorker<Void, ProgressData> worker;
    private final int totalFiles;
    private final AtomicInteger processedFileCount = new AtomicInteger(0);
    @Nullable private ExecutorService executorService;


    private record ProgressData(String fileName, @Nullable String errorMessage) {}

    public UpgradeAgentProgressDialog(Frame owner,
                                      String instructions,
                                      Service.FavoriteModel selectedFavorite,
                                      List<ProjectFile> filesToProcess,
                                      Chrome chrome) {
        super(owner, "Upgrade Agent Progress", true);
        this.totalFiles = filesToProcess.size();

        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(600, 400));

        progressBar = new JProgressBar(0, totalFiles);
        progressBar.setStringPainted(true);
        progressBar.setString("0 of " + totalFiles + " files processed");

        errorTextArea = new JTextArea();
        errorTextArea.setEditable(false);
        errorTextArea.setLineWrap(true);
        errorTextArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(errorTextArea);

        cancelButton = new JButton("Cancel");

        JPanel topPanel = new JPanel(new BorderLayout(5,5));
        topPanel.add(new JLabel("Processing files..."), BorderLayout.NORTH);
        topPanel.add(progressBar, BorderLayout.CENTER);
        topPanel.setBorder(BorderFactory.createEmptyBorder(10,10,0,10));
        add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout(5,5));
        centerPanel.add(new JLabel("Errors:"), BorderLayout.NORTH);
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        centerPanel.setBorder(BorderFactory.createEmptyBorder(0,10,0,10));
        add(centerPanel, BorderLayout.CENTER);


        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(0,10,10,10));
        add(buttonPanel, BorderLayout.SOUTH);

        worker = new SwingWorker<>() {
            // executorService is now a field of UpgradeAgentProgressDialog

            @Override
            protected Void doInBackground() {
                // Initialize the class field executorService here
                UpgradeAgentProgressDialog.this.executorService = Executors.newFixedThreadPool(Math.min(200, Math.max(1, filesToProcess.size())));
                var project = chrome.getProject();
                var contextManager = chrome.getContextManager();
                var service = contextManager.getService();

                for (ProjectFile file : filesToProcess) {
                    if (isCancelled()) {
                        break;
                    }
                    if (UpgradeAgentProgressDialog.this.executorService == null) { // Check for null
                        publish(new ProgressData(file.toString(), "Executor service not initialized."));
                        continue;
                    }
                    UpgradeAgentProgressDialog.this.executorService.submit(() -> {
                        if (Thread.currentThread().isInterrupted() || isCancelled()) {
                             publish(new ProgressData(file.toString(), "Cancelled by user."));
                            return;
                        }
                        try {
                            // First attempt with selected model
                            StreamingChatLanguageModel llm = service.getModel(selectedFavorite.modelName(), selectedFavorite.reasoning());
                            Llm llmWrapper = null;
                            if (llm != null) {
                                llmWrapper = contextManager.getLlm(llm, "Upgrade Agent: " + file.getFileName());
                            }
                            List<ChatMessage> messages = CodePrompts.instance.getSimpleFileReplaceMessages(project, file, instructions);

                            Optional<String> error;
                            if (llmWrapper == null) {
                                error = Optional.of("Selected model " + selectedFavorite.modelName() + " is unavailable or getLlm returned null.");
                            } else {
                                error = CodeAgent.executeReplace(file, llmWrapper, messages);
                            }

                            if (error.isPresent()) {
                                // Retry with grok-3-mini
                                if (isCancelled() || Thread.currentThread().isInterrupted()){
                                     publish(new ProgressData(file.toString(), "Cancelled before retry. Initial error: " + error.get()));
                                    return;
                                }
                                StreamingChatLanguageModel retryModel = service.getModel(Service.GROK_3_MINI, Service.ReasoningLevel.DEFAULT);
                                Llm retryLlmWrapper = null;
                                if (retryModel != null) {
                                    retryLlmWrapper = contextManager.getLlm(retryModel, "Upgrade Agent (retry): " + file.getFileName());
                                }
                                if (retryLlmWrapper == null) {
                                    error = Optional.of("Retry model " + Service.GROK_3_MINI + " is unavailable or getLlm returned null. Original error: " + error.get());
                                } else {
                                    error = CodeAgent.executeReplace(file, retryLlmWrapper, messages); // Re-use messages
                                }

                                if (error.isPresent()) {
                                    publish(new ProgressData(file.toString(), error.get()));
                                } else {
                                    publish(new ProgressData(file.toString(), null)); // Retry succeeded
                                }
                            } else {
                                publish(new ProgressData(file.toString(), null)); // First attempt succeeded
                            }
                        } catch (IOException e) {
                            publish(new ProgressData(file.toString(), "IO Error: " + e.getMessage()));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); // Preserve interrupt status
                            publish(new ProgressData(file.toString(), "Processing interrupted."));
                        }
                        // The `if (result.stopDetails()...)` block from HEAD is removed as the try-catch
                        // structure above now handles success/failure/interrupt reporting.
                    });
                }

                if (UpgradeAgentProgressDialog.this.executorService != null) { // Check for null
                    UpgradeAgentProgressDialog.this.executorService.shutdown();
                    try {
                        // Wait for tasks to complete or for cancellation
                        while (!UpgradeAgentProgressDialog.this.executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                            if (isCancelled()) {
                                UpgradeAgentProgressDialog.this.executorService.shutdownNow();
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        UpgradeAgentProgressDialog.this.executorService.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                }
                return null;
            }

            @Override
            protected void process(List<ProgressData> chunks) {
                for (ProgressData data : chunks) {
                    int currentCount = processedFileCount.incrementAndGet();
                    progressBar.setValue(currentCount);
                    progressBar.setString(String.format("%d of %d files processed", currentCount, totalFiles));
                    if (data.errorMessage() != null) {
                        errorTextArea.append(data.fileName() + ": " + data.errorMessage() + "\n");
                    }
                }
            }

            @Override
            protected void done() {
                if (executorService != null && !executorService.isTerminated()) {
                    executorService.shutdownNow();
                }
                cancelButton.setText("Close");
                cancelButton.removeActionListener(cancelButton.getActionListeners()[0]); // remove old cancel listener
                cancelButton.addActionListener(e -> setVisible(false));

                if (isCancelled()) {
                    progressBar.setString("Cancelled. " + processedFileCount.get() + " of " + totalFiles + " files processed.");
                    errorTextArea.append("\n--- Operation Cancelled by User ---\n");
                } else {
                     try {
                        get(); // To catch any exception from doInBackground itself
                        progressBar.setValue(totalFiles); // Ensure it shows full completion
                        progressBar.setString("Completed. " + totalFiles + " of " + totalFiles + " files processed.");
                        if (errorTextArea.getText().isEmpty()) {
                             errorTextArea.setText("All files processed successfully.");
                        } else {
                             errorTextArea.append("\n--- Operation Finished with Errors ---\n");
                        }
                    } catch (Exception e) {
                        progressBar.setString("Error during operation.");
                        errorTextArea.append("\n--- Operation Failed: " + e.getMessage() + " ---\n");
                         // Log the exception from doInBackground if any
                        logger.error("Error in UpgradeAgentSwingWorker", e);
                    }
                }
            }
        };

        cancelButton.addActionListener(e -> {
            if (!worker.isDone()) {
                worker.cancel(true);
                if (UpgradeAgentProgressDialog.this.executorService != null) {
                    UpgradeAgentProgressDialog.this.executorService.shutdownNow();
                }
            } else { // Worker is done, button is "Close"
                setVisible(false);
            }
        });
        
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE); // Prevent closing via 'X' until worker is done or explicitly cancelled
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                if (!worker.isDone()) {
                    int choice = JOptionPane.showConfirmDialog(UpgradeAgentProgressDialog.this,
                        "Are you sure you want to cancel the upgrade process?", "Confirm Cancel",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                    if (choice == JOptionPane.YES_OPTION) {
                        worker.cancel(true);
                        if (UpgradeAgentProgressDialog.this.executorService != null) {
                            UpgradeAgentProgressDialog.this.executorService.shutdownNow();
                        }
                    }
                } else {
                    setVisible(false);
                }
            }
        });


        pack();
        setLocationRelativeTo(owner);
        worker.execute();
    }
}
