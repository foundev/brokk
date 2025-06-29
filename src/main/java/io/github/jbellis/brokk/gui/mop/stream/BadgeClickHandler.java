package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.TableUtils;
import io.github.jbellis.brokk.gui.util.ContextMenuUtils;
import io.github.jbellis.brokk.EditBlock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.event.MouseEvent;

@FunctionalInterface
public interface BadgeClickHandler {
    void onBadgeClick(String badgeType, String badgeData, MouseEvent event, JComponent component);

    static BadgeClickHandler forFileClicks(IContextManager contextManager, Chrome chrome) {
        return forFileClicks(contextManager, chrome, () -> {});
    }

    static BadgeClickHandler forFileClicks(IContextManager contextManager, Chrome chrome, Runnable onRefresh) {
        return new FileClickHandler(contextManager, chrome, onRefresh);
    }

    static BadgeClickHandler forSymbolClicks(IContextManager contextManager, Chrome chrome) {
        return forSymbolClicks(contextManager, chrome, () -> {});
    }

    static BadgeClickHandler forSymbolClicks(IContextManager contextManager, Chrome chrome, Runnable onRefresh) {
        return new SymbolClickHandler(contextManager, chrome, onRefresh);
    }

    static BadgeClickHandler combined(IContextManager contextManager, Chrome chrome, Runnable onRefresh) {
        return new CombinedClickHandler(contextManager, chrome, onRefresh);
    }

    record FileClickHandler(IContextManager contextManager, Chrome chrome, Runnable onRefresh) implements BadgeClickHandler {
        private static final Logger logger = LogManager.getLogger(FileClickHandler.class);

        @Override
        public void onBadgeClick(String badgeType, String badgeData, MouseEvent event, JComponent component) {
            if ("file".equals(badgeType)) {
                handleFileClick(badgeData, event, component);
            }
        }

        private void handleFileClick(String fileName, MouseEvent event, JComponent component) {
            try {
                var projectFile = EditBlock.resolveProjectFile(contextManager, fileName);
                var fileRefData = new TableUtils.FileReferenceList.FileReferenceData(
                        fileName,
                        projectFile.toString(),
                        projectFile
                );

                showFileContextMenu(component, event, fileRefData);

            } catch (Exception e) {
                logger.debug("Failed to resolve file for badge: {}", fileName, e);

                var fileRefData = new TableUtils.FileReferenceList.FileReferenceData(
                        fileName,
                        fileName,
                        null
                );

                showFileContextMenu(component, event, fileRefData);
            }
        }

        private void showFileContextMenu(JComponent component, MouseEvent event,
                                       TableUtils.FileReferenceList.FileReferenceData fileRefData) {
            ContextMenuUtils.showFileRefMenu(component, event.getX(), event.getY(), fileRefData, chrome, onRefresh);
        }
    }

    record SymbolClickHandler(IContextManager contextManager, Chrome chrome, Runnable onRefresh) implements BadgeClickHandler {
        private static final Logger logger = LogManager.getLogger(SymbolClickHandler.class);

        @Override
        public void onBadgeClick(String badgeType, String badgeData, MouseEvent event, JComponent component) {
            if ("symbol".equals(badgeType)) {
                handleSymbolClick(badgeData, event, component);
            }
        }

        private void handleSymbolClick(String symbolId, MouseEvent event, JComponent component) {
            try {
                var analyzer = contextManager.getAnalyzerWrapper().getNonBlocking();
                if (analyzer != null) {
                    var codeUnit = analyzer.getDefinition(symbolId);
                    if (codeUnit.isPresent()) {
                        ContextMenuUtils.showSymbolMenu(component, event.getX(), event.getY(), codeUnit.get(), chrome);
                    } else {
                        logger.debug("No definition found for symbol: {}", symbolId);
                    }
                } else {
                    logger.debug("Analyzer not ready for symbol: {}", symbolId);
                }
            } catch (Exception e) {
                logger.debug("Failed to resolve symbol for badge: {}", symbolId, e);
            }
        }
    }

    record CombinedClickHandler(IContextManager contextManager, Chrome chrome, Runnable onRefresh) implements BadgeClickHandler {
        @Override
        public void onBadgeClick(String badgeType, String badgeData, MouseEvent event, JComponent component) {
            switch (badgeType) {
                case "file" -> {
                    var fileHandler = new FileClickHandler(contextManager, chrome, onRefresh);
                    fileHandler.onBadgeClick(badgeType, badgeData, event, component);
                }
                case "symbol" -> {
                    var symbolHandler = new SymbolClickHandler(contextManager, chrome, onRefresh);
                    symbolHandler.onBadgeClick(badgeType, badgeData, event, component);
                }
                default -> {}
            }
        }
    }
}
