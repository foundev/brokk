package io.github.jbellis.brokk.context;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * A frozen representation of a ContextFragment that captures its state at a point in time
 * without depending on the filesystem or analyzer. This allows ContextHistory to accurately
 * represent what the Workspace looked like when the entry was created.
 */
public final class FrozenFragment extends ContextFragment.VirtualFragment {

    private static final ConcurrentMap<String, FrozenFragment> INTERN_POOL = new ConcurrentHashMap<>();

    // Captured fragment state
    private final String contentHash; // SHA-256 hash of the content-defining fields
    private final ContextFragment.FragmentType originalType;
    private final String description;
    private final String textContent;
    private final byte[] imageBytesContent;
    private final boolean isTextFragment;
    private final String syntaxStyle;
    private final Set<CodeUnit> sources;
    private final Set<ProjectFile> files;
    
    // Metadata for unfreezing
    private final String originalClassName;
    private final Map<String, String> meta;
    
    /**
     * Private constructor for creating FrozenFragment instances.
     *
     * @param contentHash The pre-computed SHA-256 hash of content-defining fields.
     */
    private FrozenFragment(String contentHash,
                           int existingId, IContextManager contextManager,
                           ContextFragment.FragmentType originalType,
                           String description,
                           String textContent,
                           byte[] imageBytesContent,
                           boolean isTextFragment,
                           String syntaxStyle,
                           Set<CodeUnit> sources,
                           Set<ProjectFile> files,
                           String originalClassName,
                           Map<String, String> meta) {
        super(existingId, contextManager);
        this.contentHash = contentHash;
        this.originalType = originalType;
        this.description = description;
        this.textContent = textContent;
        this.imageBytesContent = imageBytesContent;
        this.isTextFragment = isTextFragment;
        this.syntaxStyle = syntaxStyle;
        this.sources = Set.copyOf(sources);
        this.files = Set.copyOf(files);
        this.originalClassName = originalClassName;
        this.meta = Map.copyOf(meta);
    }
    
    @Override
    public ContextFragment.FragmentType getType() {
        return originalType;
    }
    
    @Override
    public String shortDescription() {
        return description;
    }
    
    @Override
    public String description() {
        return description;
    }
    
    @Override
    public String text() {
        if (isTextFragment) {
            return textContent;
        } else {
            return "[Image content]";
        }
    }
    
    @Override
    public Image image() throws IOException {
        if (isTextFragment) {
            throw new UnsupportedOperationException("This fragment does not contain image content");
        }
        return bytesToImage(imageBytesContent);
    }
    
    @Override
    public String format() throws IOException, InterruptedException {
        return """
               <frozen fragmentid="%d" description="%s" originalType="%s">
               %s
               </frozen>
               """.stripIndent().formatted(id(), description, originalType.name(), text());
    }
    
    @Override
    public boolean isDynamic() {
        return false;
    }
    
    @Override
    public boolean isText() {
        return isTextFragment;
    }
    
    @Override
    public String syntaxStyle() {
        return syntaxStyle;
    }
    
    @Override
    public Set<CodeUnit> sources() {
        return sources;
    }
    
    @Override
    public Set<ProjectFile> files() {
        return files;
    }
    
    /**
     * Gets the original class name of the frozen fragment.
     * 
     * @return The original class name
     */
    public String originalClassName() {
        return originalClassName;
    }
    
    /**
     * Gets the metadata map for unfreezing.
     * 
     * @return The metadata map
     */
    public Map<String, String> meta() {
        return meta;
    }

    /**
     * Gets the content hash (SHA-256) of this frozen fragment.
     * This hash is based on the content-defining fields and is used for interning.
     *
     * @return The content hash string.
     */
    public String getContentHash() {
        return contentHash;
    }

    /**
     * Gets the image bytes content if this is an image fragment.
     *
     * @return The image bytes, or null if this is a text fragment
     */
    public byte[] imageBytesContent() {
        return imageBytesContent;
    }

    /**
     * Factory method for creating FrozenFragment from DTO data.
     * Note: This does not participate in interning; primarily for deserialization.
     * If interning of deserialized objects is desired, this would need enhancement.
     *
     * @param id The fragment ID
     * @param contextManager The context manager
     * @param originalType The original fragment type
     * @param description The fragment description
     * @param textContent The text content (null for image fragments)
     * @param imageBytesContent The image bytes (null for text fragments)
     * @param isTextFragment Whether this is a text fragment
     * @param syntaxStyle The syntax style
     * @param sources The code sources
     * @param files The project files
     * @param originalClassName The original class name
     * @param meta The metadata map
     * @return A new FrozenFragment instance
     */
    public static FrozenFragment fromDto(int id, IContextManager contextManager,
                                         ContextFragment.FragmentType originalType,
                                         String description, String textContent, byte[] imageBytesContent,
                                         boolean isTextFragment, String syntaxStyle,
                                         Set<CodeUnit> sources, Set<ProjectFile> files,
                                         String originalClassName, Map<String, String> meta) {
        // For DTO construction, we might need to re-calculate or pass a "dummy" hash
        // if the hash is strictly required by the constructor for internal consistency,
        // or adjust the constructor if the hash is only for interning.
        // For now, assuming DTO-based construction doesn't need to populate contentHash for interning logic.
        // Let's pass a placeholder or recalculate if essential for `equals`/`hashCode`.
        // However, current `equals`/`hashCode` are inherited from VirtualFragment (ID-based).
        // If interning is the sole purpose of `contentHash`, not passing it here is fine.
        // For robustness, let's calculate it, though it won't be used for interning here.
        String calculatedHash = calculateContentHash(originalType, description, textContent, imageBytesContent,
                                                     isTextFragment, syntaxStyle, sources, files,
                                                     originalClassName, meta);
        return new FrozenFragment(calculatedHash, id, contextManager, originalType, description, textContent,
                                  imageBytesContent, isTextFragment, syntaxStyle, sources, files,
                                  originalClassName, meta);
    }

    /**
     * Creates a frozen, potentially interned, representation of the given live fragment.
     * 
     * @param liveFragment The live fragment to freeze
     * @param contextManagerForFrozenFragment The context manager for the frozen fragment
     * @return A frozen representation of the fragment
     * @throws IOException If reading fragment content fails
     * @throws InterruptedException If interrupted while reading fragment content
     */
    public static FrozenFragment freeze(ContextFragment liveFragment, IContextManager contextManagerForFrozenFragment)
            throws IOException, InterruptedException {

        // Capture basic fragment data
        var type = liveFragment.getType();
        var description = liveFragment.description(); // Use live fragment's description
        var isText = liveFragment.isText();
        var syntaxStyle = liveFragment.syntaxStyle();
        var sources = liveFragment.sources(); // These are live sources
        var files = liveFragment.files();     // These are live files
        var originalClassName = liveFragment.getClass().getName();

        // Capture content
        String textContent = null;
        byte[] imageBytesContent = null;

        if (isText) {
            textContent = liveFragment.text();
        } else {
            var image = liveFragment.image();
            imageBytesContent = imageToBytes(image);
        }

        // Build metadata for unfreezing (specific to the original live fragment type)
        var meta = new HashMap<String, String>();
        switch (liveFragment) {
            case ContextFragment.ProjectPathFragment pf -> {
                meta.put("repoRoot", pf.file().getRoot().toString());
                meta.put("relPath", pf.file().getRelPath().toString());
            }
            case ContextFragment.ExternalPathFragment ef -> {
                meta.put("absPath", ef.file().absPath().toString());
            }
            case ContextFragment.ImageFileFragment iff -> {
                meta.put("absPath", iff.file().absPath().toString());
                if (iff.file() instanceof ProjectFile pf) {
                    meta.put("isProjectFile", "true");
                    meta.put("repoRoot", pf.getRoot().toString());
                    meta.put("relPath", pf.getRelPath().toString());
                }
            }
            case ContextFragment.SkeletonFragment sf -> {
                meta.put("targetIdentifiers", String.join(";", sf.getTargetIdentifiers()));
                meta.put("summaryType", sf.getSummaryType().name());
            }
            case ContextFragment.UsageFragment uf -> {
                meta.put("targetIdentifier", uf.targetIdentifier());
            }
            case ContextFragment.CallGraphFragment cgf -> {
                meta.put("methodName", cgf.getMethodName());
                meta.put("depth", String.valueOf(cgf.getDepth()));
                meta.put("isCalleeGraph", String.valueOf(cgf.isCalleeGraph()));
            }
            case ContextFragment.GitFileFragment gff -> {
                meta.put("repoRoot", gff.file().getRoot().toString());
                meta.put("relPath", gff.file().getRelPath().toString());
                meta.put("revision", gff.revision());
                // Content for GitFileFragment is already part of its definition,
                // but for hashing consistency with its nature, we include it if it drives identity.
                // Here, `gff.content()` is used to populate `textContent` if `isText` is true.
                // The `meta` map might store it redundantly or it might be fine if it's only for unfreezing.
                // For `calculateContentHash`, `textContent` (derived from `gff.content()`) will be used.
                // No need to add `gff.content()` to `meta` if `textContent` captures it.
            }
            default -> {
                // For fragment types that don't require specific metadata for unfreezing,
                // the meta map remains empty. The FrozenFragment constructor captures
                // originalClassName, which unfreeze() uses, and other standard fields.
            }
        }

        // Calculate content hash based on all identifying fields *except* the live fragment's ID.
        String contentHash = calculateContentHash(type, description, textContent, imageBytesContent,
                                                  isText, syntaxStyle, sources, files,
                                                  originalClassName, meta);

        // Use liveFragment.id() for the new FrozenFragment if it's created.
        // The INTERN_POOL ensures that if another live fragment (possibly with a different ID)
        // produces the exact same contentHash, the first FrozenFragment instance (with its ID) is reused.
        final String finalDescription = description; // effectively final for lambda
        final String finalTextContent = textContent;
        final byte[] finalImageBytesContent = imageBytesContent;
        final Set<CodeUnit> finalSources = sources;
        final Set<ProjectFile> finalFiles = files;
        final Map<String,String> finalMeta = meta;

        return INTERN_POOL.computeIfAbsent(contentHash, k -> new FrozenFragment(
                k, // The contentHash is the first argument to constructor now
                liveFragment.id(), // Use the live fragment's ID
                contextManagerForFrozenFragment,
                type,
                finalDescription,
                finalTextContent,
                finalImageBytesContent,
                isText,
                syntaxStyle,
                finalSources,
                finalFiles,
                originalClassName,
                finalMeta
        ));
    }

    private static String calculateContentHash(ContextFragment.FragmentType originalType,
                                               String description,
                                               String textContent, byte[] imageBytesContent,
                                               boolean isTextFragment, String syntaxStyle,
                                               Set<CodeUnit> sources, Set<ProjectFile> files,
                                               String originalClassName, Map<String, String> meta) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            // Helper to update digest with a string
            BiConsumer<MessageDigest, String> updateWithString = (digest, str) -> {
                if (str != null) {
                    digest.update(str.getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0); // Delimiter for null vs empty string
            };

            updateWithString.accept(md, originalType.name());
            updateWithString.accept(md, description);
            md.update(isTextFragment ? (byte) 1 : (byte) 0);
            updateWithString.accept(md, syntaxStyle);

            if (isTextFragment) {
                updateWithString.accept(md, textContent);
            } else {
                if (imageBytesContent != null) {
                    md.update(imageBytesContent);
                }
                md.update((byte) 0); // Delimiter
            }

            updateWithString.accept(md, originalClassName);

            // Canonical representation for Sets and Maps
            // For Set<CodeUnit>: Sort by fqName then kind for stability
            String sourcesString = sources.stream()
                                          .map(cu -> cu.fqName() + ":" + cu.kind().name())
                                          .sorted()
                                          .collect(Collectors.joining(","));
            updateWithString.accept(md, sourcesString);

            // For Set<ProjectFile>: Sort by absolute path for stability
            String filesString = files.stream()
                                      .map(pf -> pf.absPath().toString())
                                      .sorted()
                                      .collect(Collectors.joining(","));
            updateWithString.accept(md, filesString);

            // For Map<String, String> meta: Sort by key, then "key=value"
            String metaString = meta.entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey())
                                    .map(e -> e.getKey() + "=" + e.getValue())
                                    .collect(Collectors.joining(","));
            updateWithString.accept(md, metaString);

            byte[] digestBytes = md.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : digestBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e); // Should not happen
        }
    }

    /**
     * Recreates a live fragment from this frozen representation.
     * 
     * @param contextManagerForNewFragment The context manager for the new live fragment
     * @return A live fragment equivalent to the original
     * @throws IOException If reconstruction fails
     */
    public ContextFragment unfreeze(IContextManager contextManagerForNewFragment) throws IOException {
        return switch (originalClassName) {
            case "io.github.jbellis.brokk.context.ContextFragment$ProjectPathFragment" -> {
                var repoRoot = meta.get("repoRoot");
                var relPath = meta.get("relPath");
                if (repoRoot == null || relPath == null) {
                    throw new IllegalArgumentException("Missing metadata for ProjectPathFragment");
                }
                var file = new ProjectFile(Path.of(repoRoot), Path.of(relPath));
                yield ContextFragment.ProjectPathFragment.withId(file, this.id(), contextManagerForNewFragment);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$ExternalPathFragment" -> {
                var absPath = meta.get("absPath");
                if (absPath == null) {
                    throw new IllegalArgumentException("Missing metadata for ExternalPathFragment");
                }
                var file = new ExternalFile(Path.of(absPath));
                yield ContextFragment.ExternalPathFragment.withId(file, this.id(), contextManagerForNewFragment);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$ImageFileFragment" -> {
                var absPath = meta.get("absPath");
                if (absPath == null) {
                    throw new IllegalArgumentException("Missing metadata for ImageFileFragment");
                }
                
                BrokkFile file;
                if ("true".equals(meta.get("isProjectFile"))) {
                    var repoRoot = meta.get("repoRoot");
                    var relPath = meta.get("relPath");
                    if (repoRoot == null || relPath == null) {
                        throw new IllegalArgumentException("Missing ProjectFile metadata for ImageFileFragment");
                    }
                    file = new ProjectFile(Path.of(repoRoot), Path.of(relPath));
                } else {
                    file = new ExternalFile(Path.of(absPath));
                }
                yield ContextFragment.ImageFileFragment.withId(file, this.id(), contextManagerForNewFragment);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$SkeletonFragment" -> {
                var targetIdentifiersStr = meta.get("targetIdentifiers");
                var summaryTypeStr = meta.get("summaryType");
                if (targetIdentifiersStr == null || summaryTypeStr == null) {
                    throw new IllegalArgumentException("Missing metadata for SkeletonFragment");
                }
                var targetIdentifiers = Arrays.asList(targetIdentifiersStr.split(";"));
                var summaryType = ContextFragment.SummaryType.valueOf(summaryTypeStr);
                yield new ContextFragment.SkeletonFragment(this.id(), contextManagerForNewFragment, targetIdentifiers, summaryType);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$UsageFragment" -> {
                var targetIdentifier = meta.get("targetIdentifier");
                if (targetIdentifier == null) {
                    throw new IllegalArgumentException("Missing metadata for UsageFragment");
                }
                yield new ContextFragment.UsageFragment(this.id(), contextManagerForNewFragment, targetIdentifier);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$CallGraphFragment" -> {
                var methodName = meta.get("methodName");
                var depthStr = meta.get("depth");
                var isCalleeGraphStr = meta.get("isCalleeGraph");
                if (methodName == null || depthStr == null || isCalleeGraphStr == null) {
                    throw new IllegalArgumentException("Missing metadata for CallGraphFragment");
                }
                var depth = Integer.parseInt(depthStr);
                var isCalleeGraph = Boolean.parseBoolean(isCalleeGraphStr);
                yield new ContextFragment.CallGraphFragment(this.id(), contextManagerForNewFragment, methodName, depth, isCalleeGraph);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$GitFileFragment" -> {
                var repoRoot = meta.get("repoRoot");
                var relPath = meta.get("relPath");
                var revision = meta.get("revision");
                // The 'content' is part of textContent if it was a text fragment,
                // or imageBytesContent if it was an image.
                // For GitFileFragment, it's always text.
                if (repoRoot == null || relPath == null || revision == null) {
                    throw new IllegalArgumentException("Missing metadata for GitFileFragment (repoRoot, relPath, or revision)");
                }
                if (!isTextFragment || textContent == null) {
                     throw new IllegalArgumentException("Missing text content for GitFileFragment in frozen state");
                }
                var file = new ProjectFile(Path.of(repoRoot), Path.of(relPath));
                // Use the stored textContent as the file content for unfreezing.
                yield ContextFragment.GitFileFragment.withId(file, revision, textContent, this.id());
            }
            // For types like StringFragment, PasteTextFragment, PasteImageFragment, StacktraceFragment,
            // HistoryFragment, TaskFragment, SearchFragment:
            // Their content is already fully captured in textContent/imageBytesContent and description/meta.
            // Unfreezing them means creating a new live instance with this captured data.
            // The switch currently only handles PathFragment and dynamic VirtualFragment derivatives.
            // If a FrozenFragment represents a non-dynamic VirtualFragment, it can be returned "as-is"
            // or a new instance of the original non-dynamic type can be created.
            // For simplicity and consistency with path fragments, let's assume we try to reconstruct the original type.
            // However, the current unfreeze only reconstructs types that have specific meta entries.
            // This needs to be expanded or FrozenFragment itself needs to be usable as the "live" representation
            // if the original was simple enough.
            // For now, this will throw for unhandled originalClassNames.
            default -> {
                 // If the original was a simple, non-dynamic virtual fragment,
                 // the FrozenFragment itself can act as its "live" counterpart if its API is sufficient.
                 // Or, we could reconstruct the original simple type if necessary.
                 // For now, let's be strict.
                 throw new IllegalArgumentException("Unhandled original class for unfreezing: " + originalClassName +
                                                   ". Implement unfreezing logic if this type needs to become live.");
            }
        };
    }

    // Functional interface for BiConsumer, as it's not available in older Java versions
    // if this project targets something below Java 8 for this specific utility.
    // However, the project uses Java 21 features, so java.util.function.BiConsumer is available.
    @FunctionalInterface
    private interface BiConsumer<T, U> {
        void accept(T t, U u);
    }

    /**
     * Clears the internal intern pool. For testing purposes only.
     */
    static void clearInternPoolForTesting() {
        INTERN_POOL.clear();
    }

    /**
     * Converts an Image to a byte array in PNG format.
     * 
     * @param image The image to convert
     * @return PNG bytes, or null if image is null
     * @throws IOException If conversion fails
     */
    private static byte[] imageToBytes(Image image) throws IOException {
        if (image == null) {
            return null;
        }
        
        BufferedImage bufferedImage;
        if (image instanceof BufferedImage bi) {
            bufferedImage = bi;
        } else {
            bufferedImage = new BufferedImage(
                image.getWidth(null),
                image.getHeight(null),
                BufferedImage.TYPE_INT_ARGB
            );
            var g = bufferedImage.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        
        try (var baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "PNG", baos);
            return baos.toByteArray();
        }
    }
    
    /**
     * Converts a byte array to an Image.
     * 
     * @param bytes The byte array to convert
     * @return The converted image, or null if bytes is null
     * @throws IOException If conversion fails
     */
    private static Image bytesToImage(byte[] bytes) throws IOException {
        if (bytes == null) {
            return null;
        }
        
        try (var bais = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(bais);
        }
    }
}
