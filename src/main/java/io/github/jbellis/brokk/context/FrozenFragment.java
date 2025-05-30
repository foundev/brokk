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
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * A frozen representation of a ContextFragment that captures its state at a point in time
 * without depending on the filesystem or analyzer. This allows ContextHistory to accurately
 * represent what the Workspace looked like when the entry was created.
 */
public final class FrozenFragment extends ContextFragment.VirtualFragment {
    
    // Captured fragment state
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
     */
    private FrozenFragment(int existingId, IContextManager contextManager,
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
     * Gets the image bytes content if this is an image fragment.
     * 
     * @return The image bytes, or null if this is a text fragment
     */
    public byte[] imageBytesContent() {
        return imageBytesContent;
    }
    
    /**
     * Factory method for creating FrozenFragment from DTO data.
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
        return new FrozenFragment(id, contextManager, originalType, description, textContent,
                                 imageBytesContent, isTextFragment, syntaxStyle, sources, files,
                                 originalClassName, meta);
    }
    
    /**
     * Creates a frozen representation of the given live fragment.
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
        var description = liveFragment.description();
        var isText = liveFragment.isText();
        var syntaxStyle = liveFragment.syntaxStyle();
        var sources = liveFragment.sources();
        var files = liveFragment.files();
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
        
        // Build metadata for unfreezing
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
                meta.put("content", gff.content());
            }
            default -> {
                // For non-dynamic fragments or ones that don't need special unfreezing logic,
                // store minimal metadata
            }
        }
        
        return new FrozenFragment(
            liveFragment.id(),
            contextManagerForFrozenFragment,
            type,
            description,
            textContent,
            imageBytesContent,
            isText,
            syntaxStyle,
            sources,
            files,
            originalClassName,
            meta
        );
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
                var content = meta.get("content");
                if (repoRoot == null || relPath == null || revision == null || content == null) {
                    throw new IllegalArgumentException("Missing metadata for GitFileFragment");
                }
                var file = new ProjectFile(Path.of(repoRoot), Path.of(relPath));
                yield ContextFragment.GitFileFragment.withId(file, revision, content, this.id());
            }
            default -> throw new IllegalArgumentException("Unknown original class: " + originalClassName);
        };
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
