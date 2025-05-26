package io.github.jbellis.brokk.dto;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.Context;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.dto.FragmentDtos.*;
import io.github.jbellis.brokk.util.Messages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mapper to convert between Context domain objects and DTO representations.
 */
public class ContextMapper {
    
    private ContextMapper() {
        // Utility class - no instantiation
    }
    
    /**
     * Converts a Context domain object to its DTO representation.
     */
    public static ContextDto toDto(Context ctx) {
        var editableFilesDto = ctx.editableFiles()
                .map(ContextMapper::toProjectFileDto)
                .toList();
        
        var readonlyFilesDto = ctx.readonlyFiles()
                .map(ContextMapper::toPathFragmentDto)
                .toList();
        
        var virtualFragmentsDto = ctx.virtualFragments()
                .map(ContextMapper::toVirtualFragmentDto)
                .filter(dto -> dto != null) // Skip unsupported fragments for now
                .toList();
        
        var taskHistoryDto = ctx.getTaskHistory().stream()
                .map(ContextMapper::toTaskEntryDto)
                .toList();
        
        return new ContextDto(
                editableFilesDto,
                readonlyFilesDto,
                virtualFragmentsDto,
                taskHistoryDto,
                ContextDto.CURRENT_VERSION
        );
    }
    
    /**
     * Converts a ContextDto back to a Context domain object.
     */
    public static Context fromDto(ContextDto dto, IContextManager mgr) {
        var context = new Context(mgr, "Restored from DTO");
        
        // Convert editable files
        var editableFiles = dto.editableFiles().stream()
                .map(ContextMapper::fromProjectFileDto)
                .map(ContextFragment.ProjectPathFragment::new)
                .toList();
        
        if (!editableFiles.isEmpty()) {
            context = context.addEditableFiles(editableFiles);
        }
        
        // Convert readonly files
        var readonlyFiles = dto.readonlyFiles().stream()
                .map(ContextMapper::fromPathFragmentDto)
                .map(ContextFragment::toPathFragment)
                .toList();
        
        if (!readonlyFiles.isEmpty()) {
            context = context.addReadonlyFiles(readonlyFiles);
        }
        
        // Convert virtual fragments
        for (var virtualDto : dto.virtualFragments()) {
            var fragment = fromVirtualFragmentDto(virtualDto);
            if (fragment != null) {
                context = context.addVirtualFragment(fragment);
            }
        }
        
        // Convert task history
        for (var taskDto : dto.taskHistory()) {
            var taskEntry = fromTaskEntryDto(taskDto);
            if (taskEntry != null) {
                // Add the task entry to history - we need to simulate the addHistoryEntry process
                // For now, we'll reconstruct the context with the task history
                var newTaskHistory = new ArrayList<>(context.getTaskHistory());
                newTaskHistory.add(taskEntry);
                context = context.withCompressedHistory(newTaskHistory);
            }
        }
        
        return context;
    }
    
    private static ProjectFileDto toProjectFileDto(ContextFragment.ProjectPathFragment fragment) {
        var file = fragment.file();
        return new ProjectFileDto(file.getRoot().toString(), file.getRelPath().toString());
    }
    
    private static PathFragmentDto toPathFragmentDto(ContextFragment.PathFragment fragment) {
        var file = fragment.file();
        if (file instanceof ProjectFile pf) {
            return new ProjectFileDto(pf.getRoot().toString(), pf.getRelPath().toString());
        } else if (file instanceof ExternalFile ef) {
            return new ExternalFileDto(ef.getPath().toString());
        } else {
            throw new IllegalArgumentException("Unsupported file type: " + file.getClass().getName());
        }
    }
    
    private static VirtualFragmentDto toVirtualFragmentDto(ContextFragment.VirtualFragment fragment) {
        return switch (fragment) {
            case ContextFragment.SearchFragment searchFragment -> {
                var sourcesDto = searchFragment.sources(null).stream()
                        .map(ContextMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                var messagesDto = searchFragment.messages().stream()
                        .map(ContextMapper::toChatMessageDto)
                        .toList();
                yield new SearchFragmentDto(
                        searchFragment.query(),
                        searchFragment.explanation(),
                        sourcesDto,
                        messagesDto
                );
            }
            case ContextFragment.TaskFragment taskFragment -> {
                var messagesDto = taskFragment.messages().stream()
                        .map(ContextMapper::toChatMessageDto)
                        .toList();
                yield new TaskFragmentDto(messagesDto, taskFragment.description());
            }
            case ContextFragment.StringFragment stringFragment -> {
                yield new StringFragmentDto(
                        stringFragment.text(),
                        stringFragment.description(),
                        stringFragment.syntaxStyle()
                );
            }
            case ContextFragment.SkeletonFragment skeletonFragment -> {
                var skeletonsDto = skeletonFragment.skeletons().entrySet().stream()
                        .map(entry -> new SkeletonEntryDto(
                                toCodeUnitDto(entry.getKey()),
                                entry.getValue()
                        ))
                        .toList();
                yield new SkeletonFragmentDto(skeletonsDto);
            }
            case ContextFragment.UsageFragment usageFragment -> {
                var classesDto = usageFragment.sources(null).stream()
                        .map(ContextMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                yield new UsageFragmentDto(
                        usageFragment.targetIdentifier(),
                        classesDto,
                        usageFragment.text()
                );
            }
            default -> null; // Skip complex fragments like PasteFragment, StacktraceFragment for now
        };
    }
    
    private static TaskEntryDto toTaskEntryDto(TaskEntry entry) {
        TaskFragmentDto logDto = null;
        if (entry.log() != null) {
            var messagesDto = entry.log().messages().stream()
                    .map(ContextMapper::toChatMessageDto)
                    .toList();
            logDto = new TaskFragmentDto(messagesDto, entry.log().description());
        }
        
        return new TaskEntryDto(entry.sequence(), logDto, entry.summary());
    }
    
    private static ChatMessageDto toChatMessageDto(ChatMessage message) {
        return new ChatMessageDto(message.type().name().toLowerCase(), Messages.getRepr(message));
    }
    
    private static ProjectFile fromProjectFileDto(ProjectFileDto dto) {
        return new ProjectFile(Path.of(dto.repoRoot()), Path.of(dto.relPath()));
    }
    
    private static ExternalFile fromExternalFileDto(ExternalFileDto dto) {
        return new ExternalFile(Path.of(dto.absPath()));
    }
    
    private static BrokkFile fromPathFragmentDto(PathFragmentDto dto) {
        return switch (dto) {
            case ProjectFileDto pfd -> fromProjectFileDto(pfd);
            case ExternalFileDto efd -> fromExternalFileDto(efd);
            case ImageFileDto ifd -> throw new UnsupportedOperationException("ImageFileDto conversion not yet implemented");
        };
    }
    
    private static ContextFragment.VirtualFragment fromVirtualFragmentDto(VirtualFragmentDto dto) {
        return switch (dto) {
            case SearchFragmentDto searchDto -> {
                var sources = searchDto.sources().stream()
                        .map(ContextMapper::fromCodeUnitDto)
                        .collect(Collectors.toSet());
                yield new ContextFragment.SearchFragment(
                        searchDto.query(),
                        searchDto.explanation(),
                        sources
                );
            }
            case TaskFragmentDto taskDto -> {
                var messages = taskDto.messages().stream()
                        .map(ContextMapper::fromChatMessageDto)
                        .toList();
                yield new ContextFragment.TaskFragment(messages, taskDto.sessionName());
            }
            case StringFragmentDto stringDto -> {
                yield new ContextFragment.StringFragment(
                        stringDto.text(),
                        stringDto.description(),
                        stringDto.syntaxStyle()
                );
            }
            case SkeletonFragmentDto skeletonDto -> {
                var skeletons = skeletonDto.skeletons().stream()
                        .collect(Collectors.toMap(
                                entry -> fromCodeUnitDto(entry.codeUnit()),
                                SkeletonEntryDto::skeleton
                        ));
                yield new ContextFragment.SkeletonFragment(skeletons);
            }
            case UsageFragmentDto usageDto -> {
                var classes = usageDto.classes().stream()
                        .map(ContextMapper::fromCodeUnitDto)
                        .collect(Collectors.toSet());
                yield new ContextFragment.UsageFragment(
                        usageDto.targetIdentifier(),
                        classes,
                        usageDto.code()
                );
            }
        };
    }
    
    private static ChatMessage fromChatMessageDto(ChatMessageDto dto) {
        // Convert role string back to ChatMessage
        return switch (dto.role().toLowerCase()) {
            case "user" -> dev.langchain4j.data.message.UserMessage.from(dto.content());
            case "ai" -> dev.langchain4j.data.message.AiMessage.from(dto.content());
            case "system" -> dev.langchain4j.data.message.SystemMessage.from(dto.content());
            default -> throw new IllegalArgumentException("Unsupported message role: " + dto.role());
        };
    }
    
    private static CodeUnitDto toCodeUnitDto(CodeUnit codeUnit) {
        return new CodeUnitDto(
                codeUnit.source().toString(),
                codeUnit.kind().name(),
                codeUnit.packageName(),
                codeUnit.shortName()
        );
    }
    
    private static TaskEntry fromTaskEntryDto(TaskEntryDto dto) {
        if (dto.log() != null) {
            var messages = dto.log().messages().stream()
                    .map(ContextMapper::fromChatMessageDto)
                    .toList();
            var taskFragment = new ContextFragment.TaskFragment(messages, dto.log().sessionName());
            return new TaskEntry(dto.sequence(), taskFragment, null);
        } else if (dto.summary() != null) {
            return TaskEntry.fromCompressed(dto.sequence(), dto.summary());
        } else {
            return null; // Invalid TaskEntry
        }
    }
    
    private static CodeUnit fromCodeUnitDto(CodeUnitDto dto) {
        // Note: This assumes we can reconstruct the ProjectFile from the relative path
        // In a real implementation, we might need to pass the project root through the context
        // Use a default absolute path for now - this is a limitation of the current DTO approach
        var tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        var source = new ProjectFile(tempRoot, dto.fileRelPath());
        var kind = io.github.jbellis.brokk.analyzer.CodeUnitType.valueOf(dto.kind());
        return new CodeUnit(source, kind, dto.packageName(), dto.shortName());
    }
}
