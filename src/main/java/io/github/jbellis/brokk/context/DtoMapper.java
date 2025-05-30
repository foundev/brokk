package io.github.jbellis.brokk.context;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.FragmentDtos.*;
import io.github.jbellis.brokk.util.Messages;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Mapper to convert between Context domain objects and DTO representations.
 */
public class DtoMapper {
    
    private DtoMapper() {
        // Utility class - no instantiation
    }
    
    /**
     * Converts a Context domain object to its DTO representation.
     */
    public static ContextDto toDto(Context ctx) {
        var editableFilesDto = ctx.editableFiles()
                .map(DtoMapper::toProjectFileDto)
                .toList();
        
        var readonlyFilesDto = ctx.readonlyFiles()
                .map(DtoMapper::toPathFragmentDto)
                .toList();
        
        var virtualFragmentsDto = ctx.virtualFragments()
                .map(DtoMapper::toVirtualFragmentDto)
                .filter(dto -> dto != null) // Skip unsupported fragments for now
                .toList();
        
        var taskHistoryDto = ctx.getTaskHistory().stream()
                .map(DtoMapper::toTaskEntryDto)
                .toList();
        
        return new ContextDto(editableFilesDto,
                readonlyFilesDto,
                virtualFragmentsDto,
                taskHistoryDto);
    }
    
    /**
     * Converts a ContextDto back to a Context domain object.
     */
    public static Context fromDto(ContextDto dto, IContextManager mgr) {
        var context = new Context(mgr, "Restored from DTO");
        
        // Convert editable files
        var editableFiles = dto.editableFiles().stream()
                .map(projectDto -> {
                    var file = new ProjectFile(Path.of(projectDto.repoRoot()), Path.of(projectDto.relPath()));
                    return ContextFragment.ProjectPathFragment.withId(file, projectDto.id(), mgr); // Pass mgr
                })
                .collect(Collectors.toList()); // Ensure it's a List of ProjectPathFragment

        if (!editableFiles.isEmpty()) {
            context = context.addEditableFiles(editableFiles);
        }

        // Convert readonly files
        var readonlyFiles = dto.readonlyFiles().stream()
                .<ContextFragment.PathFragment>map(pathDto -> {
                    return switch (pathDto) {
                        case GitFileFragmentDto gitDto -> {
                            var projectFile = new ProjectFile(Path.of(gitDto.repoRoot()), Path.of(gitDto.relPath()));
                            yield ContextFragment.GitFileFragment.withId(projectFile, gitDto.revision(), gitDto.content(), gitDto.id());
                        }
                        case ImageFileDto imageDto -> {
                            var file = fromPathFragmentDto(imageDto);
                            yield ContextFragment.ImageFileFragment.withId(file, imageDto.id(), mgr);
                        }
                        case ProjectFileDto projectDto -> {
                            var file = new ProjectFile(Path.of(projectDto.repoRoot()), Path.of(projectDto.relPath()));
                            yield ContextFragment.ProjectPathFragment.withId(file, projectDto.id(), mgr);
                        }
                        case ExternalFileDto externalDto -> {
                            var file = new ExternalFile(Path.of(externalDto.absPath()));
                            yield ContextFragment.ExternalPathFragment.withId(file, externalDto.id(), mgr);
                        }
                    };
                })
                .toList();
        
        if (!readonlyFiles.isEmpty()) {
            context = context.addReadonlyFiles(readonlyFiles);
        }
        
        // Convert virtual fragments
        for (var virtualDto : dto.virtualFragments()) {
            var fragment = fromVirtualFragmentDto((FragmentDtos.VirtualFragmentDto)virtualDto, mgr); // Explicit cast
            context = context.addVirtualFragment(fragment);
        }
        
        // Convert task history
        for (var taskDto : dto.taskHistory()) {
            var taskEntry = fromTaskEntryDto(taskDto, mgr); // Pass mgr here
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

    private static ContextFragment.VirtualFragment fromVirtualFragmentDto(FragmentDtos.VirtualFragmentDto dto, IContextManager mgr) {
        return switch (dto) {
            case FrozenFragmentDto frozenDto -> {
                byte[] imageBytes = null;
                if (frozenDto.base64ImageContent() != null) {
                    imageBytes = Base64.getDecoder().decode(frozenDto.base64ImageContent());
                }
                
                var sources = frozenDto.sources().stream()
                        .map(DtoMapper::fromCodeUnitDto)
                        .collect(Collectors.toSet());
                        
                var files = frozenDto.files().stream()
                        .map(DtoMapper::fromProjectFileDto)
                        .collect(Collectors.toSet());
                
                var originalType = ContextFragment.FragmentType.valueOf(frozenDto.originalType());
                
                yield FrozenFragment.fromDto(
                    frozenDto.id(),
                    mgr,
                    originalType,
                    frozenDto.description(),
                    frozenDto.textContent(),
                    imageBytes,
                    frozenDto.isTextFragment(),
                    frozenDto.syntaxStyle(),
                    sources,
                    files,
                    frozenDto.originalClassName(),
                    frozenDto.meta()
                );
            }
            case SearchFragmentDto searchDto -> {
                var sources = searchDto.sources().stream()
                        .map(DtoMapper::fromCodeUnitDto)
                        .collect(Collectors.toSet());
                var messages = searchDto.messages().stream()
                        .map(DtoMapper::fromChatMessageDto)
                        .toList();
                // SearchFragment constructor takes (IContextManager, String sessionName, List<ChatMessage> messages, Set<CodeUnit> sources)
                yield new ContextFragment.SearchFragment(mgr, searchDto.query(), messages, sources);
            }
            case TaskFragmentDto taskDto -> {
                var messages = taskDto.messages().stream()
                        .map(DtoMapper::fromChatMessageDto)
                        .toList();
                yield new ContextFragment.TaskFragment(taskDto.id(), mgr, messages, taskDto.sessionName());
            }
            case StringFragmentDto stringDto -> {
                yield new ContextFragment.StringFragment(stringDto.id(), mgr, stringDto.text(), stringDto.description(), stringDto.syntaxStyle());
            }
            case SkeletonFragmentDto skeletonDto -> {
                yield new ContextFragment.SkeletonFragment(skeletonDto.id(), mgr, skeletonDto.targetIdentifiers(), ContextFragment.SummaryType.valueOf(skeletonDto.summaryType()));
            }
            case UsageFragmentDto usageDto -> {
                yield new ContextFragment.UsageFragment(usageDto.id(), mgr, usageDto.targetIdentifier());
            }
            case PasteTextFragmentDto pasteTextDto -> {
                yield new ContextFragment.PasteTextFragment(pasteTextDto.id(), mgr, pasteTextDto.text(), CompletableFuture.completedFuture(pasteTextDto.description()));
            }
            case PasteImageFragmentDto pasteImageDto -> {
                Image image = base64ToImage(pasteImageDto.base64ImageData());
                yield new ContextFragment.PasteImageFragment(pasteImageDto.id(), mgr, image, CompletableFuture.completedFuture(pasteImageDto.description()));
            }
            case StacktraceFragmentDto stacktraceDto -> {
                var sources = stacktraceDto.sources().stream()
                        .map(DtoMapper::fromCodeUnitDto)
                        .collect(Collectors.toSet());
                yield new ContextFragment.StacktraceFragment(stacktraceDto.id(), mgr, sources, stacktraceDto.original(), stacktraceDto.exception(), stacktraceDto.code());
            }
            case CallGraphFragmentDto callGraphDto -> {
                yield new ContextFragment.CallGraphFragment(callGraphDto.id(), mgr, callGraphDto.methodName(), callGraphDto.depth(), callGraphDto.isCalleeGraph());
            }
            case HistoryFragmentDto historyDto -> {
                var historyEntries = historyDto.history().stream()
                        .map(taskEntryDto -> fromTaskEntryDto(taskEntryDto, mgr))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                yield new ContextFragment.HistoryFragment(historyDto.id(), mgr, historyEntries);
            }
        };
    }
    
    private static ProjectFileDto toProjectFileDto(ContextFragment.ProjectPathFragment fragment) {
        var file = fragment.file();
        return new ProjectFileDto(fragment.id(), file.getRoot().toString(), file.getRelPath().toString());
    }
    
    private static ProjectFileDto toProjectFileDto(ProjectFile pf) {
        return new ProjectFileDto(0, pf.getRoot().toString(), pf.getRelPath().toString());
    }
    
    private static PathFragmentDto toPathFragmentDto(ContextFragment.PathFragment fragment) { // IContextManager not needed here
        return switch (fragment) {
            case ContextFragment.ProjectPathFragment projectFragment -> {
                var pf = projectFragment.file();
                yield new ProjectFileDto(projectFragment.id(), pf.getRoot().toString(), pf.getRelPath().toString());
            }
            case ContextFragment.ExternalPathFragment externalFragment -> {
                var ef = externalFragment.file();
                yield new ExternalFileDto(externalFragment.id(), ef.getPath().toString());
            }
            case ContextFragment.GitFileFragment gitFileFragment -> {
                var pf = gitFileFragment.file();
                yield new GitFileFragmentDto(
                    gitFileFragment.id(),
                    pf.getRoot().toString(),
                    pf.getRelPath().toString(),
                    gitFileFragment.revision(),
                    gitFileFragment.content()
                );
            }
            case ContextFragment.ImageFileFragment imageFileFragment -> {
                var file = imageFileFragment.file();
                String absPath = file.absPath().toString();
                // Try to determine media type from file extension
                String fileName = file.getFileName().toLowerCase();
                String mediaType = null;
                if (fileName.endsWith(".png")) {
                    mediaType = "image/png";
                } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                    mediaType = "image/jpeg";
                } else if (fileName.endsWith(".gif")) {
                    mediaType = "image/gif";
                }
                yield new ImageFileDto(imageFileFragment.id(), absPath, mediaType);
            }
        };
    }
    
    private static VirtualFragmentDto toVirtualFragmentDto(ContextFragment.VirtualFragment fragment) { // IContextManager not needed here as dynamic fragments store params
        // Handle FrozenFragment first
        if (fragment instanceof FrozenFragment ff) {
            try {
                String base64ImageContent = null;
                if (!ff.isText()) {
                    var imageBytes = ff.imageBytesContent();
                    if (imageBytes != null) {
                        base64ImageContent = Base64.getEncoder().encodeToString(imageBytes);
                    }
                }
                
                var sourcesDto = ff.sources().stream()
                        .map(DtoMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                        
                var filesDto = ff.files().stream()
                        .map(DtoMapper::toProjectFileDto)
                        .collect(Collectors.toSet());
                
                return new FrozenFragmentDto(
                    ff.id(),
                    ff.getType().name(),
                    ff.description(),
                    ff.isText() ? ff.text() : null,
                    base64ImageContent,
                    ff.isText(),
                    ff.syntaxStyle(),
                    sourcesDto,
                    filesDto,
                    ff.originalClassName(),
                    ff.meta()
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize FrozenFragment", e);
            }
        }
        
        return switch (fragment) {
            case ContextFragment.SearchFragment searchFragment -> {
                var sourcesDto = searchFragment.sources().stream() // No analyzer needed for pre-computed
                        .map(DtoMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                var messagesDto = searchFragment.messages().stream()
                        .map(DtoMapper::toChatMessageDto)
                        .toList();
                yield new SearchFragmentDto(
                        searchFragment.id(),
                        searchFragment.description(), // sessionName
                        "", // explanation - no longer available as separate field
                        sourcesDto,
                        messagesDto
                );
            }
            case ContextFragment.TaskFragment taskFragment -> {
                var messagesDto = taskFragment.messages().stream()
                        .map(DtoMapper::toChatMessageDto)
                        .toList();
                yield new TaskFragmentDto(taskFragment.id(), messagesDto, taskFragment.description());
            }
            case ContextFragment.StringFragment stringFragment -> {
                yield new StringFragmentDto(
                        stringFragment.id(),
                        stringFragment.text(), // StringFragment is non-dynamic
                        stringFragment.description(),
                        stringFragment.syntaxStyle()
                );
            }
            case ContextFragment.SkeletonFragment skeletonFragment -> {
                yield new SkeletonFragmentDto(
                        skeletonFragment.id(),
                        skeletonFragment.getTargetIdentifiers(),
                        skeletonFragment.getSummaryType().name()
                );
            }
            case ContextFragment.UsageFragment usageFragment -> {
                yield new UsageFragmentDto(
                        usageFragment.id(),
                        usageFragment.targetIdentifier()
                );
            }
            case ContextFragment.PasteTextFragment pasteTextFragment -> {
                // Block for up to 10 seconds to get the completed description
                String description;
                try {
                    var future = pasteTextFragment.descriptionFuture;
                    String fullDescription = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    // Remove "Paste of " prefix to avoid duplication during deserialization
                    description = fullDescription.startsWith("Paste of ")
                        ? fullDescription.substring("Paste of ".length())
                        : fullDescription;
                } catch (java.util.concurrent.TimeoutException e) {
                    description = "(Paste description timed out)";
                } catch (Exception e) {
                    description = "(Error getting paste description)";
                }
                yield new PasteTextFragmentDto(pasteTextFragment.id(), pasteTextFragment.text(), description); // PasteTextFragment is non-dynamic
            }
            case ContextFragment.PasteImageFragment pasteImageFragment -> {
                // Block for up to 10 seconds to get the completed description
                String description;
                try {
                    var future = pasteImageFragment.descriptionFuture;
                    String fullDescription = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    // Remove "Paste of " prefix to avoid duplication during deserialization
                    description = fullDescription.startsWith("Paste of ")
                        ? fullDescription.substring("Paste of ".length())
                        : fullDescription;
                } catch (java.util.concurrent.TimeoutException e) {
                    description = "(Paste description timed out)";
                } catch (Exception e) {
                    description = "(Error getting paste description)";
                }
                // Convert Image to base64
                String base64ImageData = imageToBase64(pasteImageFragment.image());
                yield new PasteImageFragmentDto(pasteImageFragment.id(), base64ImageData, description);
            }
            case ContextFragment.StacktraceFragment stacktraceFragment -> {
                var sourcesDto = stacktraceFragment.sources().stream() // No analyzer needed for pre-computed
                        .map(DtoMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                yield new StacktraceFragmentDto(
                        stacktraceFragment.id(),
                        sourcesDto,
                        stacktraceFragment.text().split("\n\nStacktrace methods in this project:\n\n")[0], // original // StacktraceFragment is non-dynamic
                        stacktraceFragment.description().substring("stacktrace of ".length()), // exception
                        stacktraceFragment.text().contains("\n\nStacktrace methods in this project:\n\n")
                            ? stacktraceFragment.text().split("\n\nStacktrace methods in this project:\n\n")[1]
                            : "" // code
                );
            }
            case ContextFragment.CallGraphFragment callGraphFragment -> {
                yield new CallGraphFragmentDto(
                        callGraphFragment.id(),
                        callGraphFragment.getMethodName(),
                        callGraphFragment.getDepth(),
                        callGraphFragment.isCalleeGraph()
                );
            }
            case ContextFragment.HistoryFragment historyFragment -> {
                var historyDto = historyFragment.entries().stream()
                        .map(DtoMapper::toTaskEntryDto)
                        .toList();
                yield new HistoryFragmentDto(historyFragment.id(), historyDto);
            }
            default -> null; // Skip unsupported fragments
        };
    }
    
    private static TaskEntryDto toTaskEntryDto(TaskEntry entry) { // IContextManager not needed for serialization
        TaskFragmentDto logDto = null;
        if (entry.log() != null) {
            var messagesDto = entry.log().messages().stream()
                    .map(DtoMapper::toChatMessageDto)
                    .toList();
            logDto = new TaskFragmentDto(entry.log().id(), messagesDto, entry.log().description());
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

    private static BrokkFile fromPathFragmentDto(PathFragmentDto dto) { // IContextManager not needed for this helper
        return switch (dto) {
            case ProjectFileDto pfd -> fromProjectFileDto(pfd);
            case ExternalFileDto efd -> fromExternalFileDto(efd);
            case ImageFileDto ifd -> {
                // For ImageFileDto, we need to determine if it's a ProjectFile or ExternalFile based on the path
                Path path = Path.of(ifd.absPath());
                if (path.isAbsolute()) {
                    yield new ExternalFile(path);
                } else {
                    // This is problematic as we don't have the root - for now assume it's external
                    yield new ExternalFile(path.toAbsolutePath());
                }
            }
            case GitFileFragmentDto gfd -> fromProjectFileDto(new ProjectFileDto(0, gfd.repoRoot(), gfd.relPath()));
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

    private static CodeUnitDto toCodeUnitDto(CodeUnit codeUnit) { // IContextManager not needed for serialization
        ProjectFile pf = codeUnit.source();
        ProjectFileDto pfd = new ProjectFileDto(0, pf.getRoot().toString(), pf.getRelPath().toString());
        return new CodeUnitDto(
                pfd,
                codeUnit.kind().name(),
                codeUnit.packageName(),
                codeUnit.shortName()
        );
    }

    private static TaskEntry fromTaskEntryDto(TaskEntryDto dto, IContextManager mgr) {
        if (dto.log() != null) {
            var messages = dto.log().messages().stream()
                    .map(DtoMapper::fromChatMessageDto)
                    .toList();
            // Pass mgr to TaskFragment constructor
            var taskFragment = new ContextFragment.TaskFragment(dto.log().id(), mgr, messages, dto.log().sessionName());
            return new TaskEntry(dto.sequence(), taskFragment, null);
        } else if (dto.summary() != null) {
            return TaskEntry.fromCompressed(dto.sequence(), dto.summary());
        } else {
            return null; // Invalid TaskEntry
        }
    }

    private static CodeUnit fromCodeUnitDto(CodeUnitDto dto) { // IContextManager not needed as ProjectFileDto has full path info
        ProjectFileDto pfd = dto.sourceFile();
        ProjectFile source = new ProjectFile(Path.of(pfd.repoRoot()), Path.of(pfd.relPath()));
        var kind = io.github.jbellis.brokk.analyzer.CodeUnitType.valueOf(dto.kind());
        return new CodeUnit(source, kind, dto.packageName(), dto.shortName());
    }
    
    /**
     * Converts an Image to base64-encoded PNG data.
     */
    private static String imageToBase64(Image image) {
        try (var baos = new ByteArrayOutputStream()) {
            // Convert Image to BufferedImage if needed
            java.awt.image.BufferedImage bufferedImage;
            if (image instanceof java.awt.image.BufferedImage bi) {
                bufferedImage = bi;
            } else {
                bufferedImage = new java.awt.image.BufferedImage(
                    image.getWidth(null), 
                    image.getHeight(null), 
                    java.awt.image.BufferedImage.TYPE_INT_ARGB
                );
                var g = bufferedImage.createGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();
            }
            
            ImageIO.write(bufferedImage, "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to convert image to base64", e);
        }
    }
    
    /**
     * Converts base64-encoded image data back to an Image.
     */
    private static Image base64ToImage(String base64Data) {
        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            throw new RuntimeException("Failed to convert base64 to image", e);
        }
    }
}
