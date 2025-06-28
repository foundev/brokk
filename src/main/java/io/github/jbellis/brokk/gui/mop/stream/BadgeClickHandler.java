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
}
