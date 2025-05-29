package io.github.jbellis.brokk.dto;

import io.github.jbellis.brokk.dto.FragmentDtos.*;
import java.util.List;

/**
 * DTO record for Context containing only persistent fields.
 * Excludes transient runtime fields like contextManager, action, parsedOutput, etc.
 */
public record ContextDto(List<ProjectFileDto> editableFiles,
                         List<PathFragmentDto> readonlyFiles,
                         List<VirtualFragmentDto> virtualFragments,
                         List<TaskEntryDto> taskHistory)
{
    public ContextDto {
        // Defensive copying for immutability
        editableFiles = editableFiles != null ? List.copyOf(editableFiles) : List.of();
        readonlyFiles = readonlyFiles != null ? List.copyOf(readonlyFiles) : List.of();
        virtualFragments = virtualFragments != null ? List.copyOf(virtualFragments) : List.of();
        taskHistory = taskHistory != null ? List.copyOf(taskHistory) : List.of();
    }
}
