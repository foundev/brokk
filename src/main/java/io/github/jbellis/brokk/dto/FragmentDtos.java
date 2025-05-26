package io.github.jbellis.brokk.dto;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sealed interfaces and records for fragment DTOs with Jackson polymorphic support.
 */
public class FragmentDtos {
    
    /**
     * Sealed interface for path-based fragments (files).
     */
    @JsonTypeInfo(use = Id.CLASS, include = As.PROPERTY, property = "type")
    public sealed interface PathFragmentDto permits ProjectFileDto, ExternalFileDto, ImageFileDto {
    }
    
    /**
     * Sealed interface for virtual fragments (non-file content).
     */
    @JsonTypeInfo(use = Id.CLASS, include = As.PROPERTY, property = "type")
    public sealed interface VirtualFragmentDto permits TaskFragmentDto, StringFragmentDto, SearchFragmentDto, SkeletonFragmentDto, UsageFragmentDto {
    }
    
    /**
     * DTO for ProjectFile - contains root and relative path as strings.
     */
    public record ProjectFileDto(String repoRoot, String relPath) implements PathFragmentDto {
        public ProjectFileDto {
            if (repoRoot == null || repoRoot.isEmpty()) {
                throw new IllegalArgumentException("repoRoot cannot be null or empty");
            }
            if (relPath == null || relPath.isEmpty()) {
                throw new IllegalArgumentException("relPath cannot be null or empty");
            }
        }
    }
    
    /**
     * DTO for ExternalFile - contains absolute path as string.
     */
    public record ExternalFileDto(String absPath) implements PathFragmentDto {
        public ExternalFileDto {
            if (absPath == null || absPath.isEmpty()) {
                throw new IllegalArgumentException("absPath cannot be null or empty");
            }
        }
    }
    
    /**
     * DTO for ImageFile - contains absolute path and media type.
     */
    public record ImageFileDto(String absPath, String mediaType) implements PathFragmentDto {
        public ImageFileDto {
            if (absPath == null || absPath.isEmpty()) {
                throw new IllegalArgumentException("absPath cannot be null or empty");
            }
            // mediaType can be null for unknown types
        }
    }
    
    /**
     * DTO for TaskEntry - represents a task history entry.
     */
    public record TaskEntryDto(int sequence, TaskFragmentDto log, String summary) {
        public TaskEntryDto {
            // Exactly one of log or summary must be non-null (same constraint as TaskEntry)
            if ((log == null) == (summary == null)) {
                throw new IllegalArgumentException("Exactly one of log or summary must be non-null");
            }
            if (summary != null && summary.isEmpty()) {
                throw new IllegalArgumentException("summary cannot be empty when present");
            }
        }
    }
    
    /**
     * DTO for TaskFragment - represents a session's chat messages.
     */
    public record TaskFragmentDto(List<ChatMessageDto> messages, String sessionName) implements VirtualFragmentDto {
        public TaskFragmentDto {
            messages = messages != null ? List.copyOf(messages) : List.of();
            if (sessionName == null) {
                throw new IllegalArgumentException("sessionName cannot be null");
            }
        }
    }
    
    /**
     * DTO for ChatMessage - simplified representation with role and content.
     */
    public record ChatMessageDto(String role, String content) {
        public ChatMessageDto {
            if (role == null || role.isEmpty()) {
                throw new IllegalArgumentException("role cannot be null or empty");
            }
            if (content == null) {
                throw new IllegalArgumentException("content cannot be null");
            }
        }
    }
    
    /**
     * DTO for StringFragment - contains text content with description and syntax style.
     */
    public record StringFragmentDto(String text, String description, String syntaxStyle) implements VirtualFragmentDto {
        public StringFragmentDto {
            if (text == null) {
                throw new IllegalArgumentException("text cannot be null");
            }
            if (description == null) {
                throw new IllegalArgumentException("description cannot be null");
            }
            if (syntaxStyle == null) {
                throw new IllegalArgumentException("syntaxStyle cannot be null");
            }
        }
    }
    
    /**
     * DTO for SearchFragment - contains search query, explanation, sources and messages.
     */
    public record SearchFragmentDto(String query, String explanation, Set<CodeUnitDto> sources, List<ChatMessageDto> messages) implements VirtualFragmentDto {
        public SearchFragmentDto {
            if (query == null) {
                throw new IllegalArgumentException("query cannot be null");
            }
            if (explanation == null) {
                throw new IllegalArgumentException("explanation cannot be null");
            }
            sources = sources != null ? Set.copyOf(sources) : Set.of();
            messages = messages != null ? List.copyOf(messages) : List.of();
        }
    }
    
    /**
     * DTO for SkeletonFragment - contains mapping of code units to their skeleton representations.
     * Uses a list of pairs instead of a map to avoid Jackson key serialization issues.
     */
    public record SkeletonFragmentDto(List<SkeletonEntryDto> skeletons) implements VirtualFragmentDto {
        public SkeletonFragmentDto {
            skeletons = skeletons != null ? List.copyOf(skeletons) : List.of();
        }
    }
    
    /**
     * Helper record for SkeletonFragment entries to avoid Map key serialization issues.
     */
    public record SkeletonEntryDto(CodeUnitDto codeUnit, String skeleton) {
        public SkeletonEntryDto {
            if (codeUnit == null) {
                throw new IllegalArgumentException("codeUnit cannot be null");
            }
            if (skeleton == null) {
                throw new IllegalArgumentException("skeleton cannot be null");
            }
        }
    }
    
    /**
     * DTO for UsageFragment - contains target identifier, related classes and code.
     */
    public record UsageFragmentDto(String targetIdentifier, Set<CodeUnitDto> classes, String code) implements VirtualFragmentDto {
        public UsageFragmentDto {
            if (targetIdentifier == null) {
                throw new IllegalArgumentException("targetIdentifier cannot be null");
            }
            if (code == null) {
                throw new IllegalArgumentException("code cannot be null");
            }
            classes = classes != null ? Set.copyOf(classes) : Set.of();
        }
    }
    
    /**
     * DTO for CodeUnit - represents a named code element.
     */
    public record CodeUnitDto(String fileRelPath, String kind, String packageName, String shortName) {
        public CodeUnitDto {
            if (fileRelPath == null) {
                throw new IllegalArgumentException("fileRelPath cannot be null");
            }
            if (kind == null || kind.isEmpty()) {
                throw new IllegalArgumentException("kind cannot be null or empty");
            }
            if (packageName == null) {
                throw new IllegalArgumentException("packageName cannot be null");
            }
            if (shortName == null || shortName.isEmpty()) {
                throw new IllegalArgumentException("shortName cannot be null or empty");
            }
        }
    }
}
