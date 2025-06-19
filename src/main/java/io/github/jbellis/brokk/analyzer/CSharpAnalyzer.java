package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCSharp;

import java.util.Collections;
import java.util.Set;

public final class CSharpAnalyzer extends TreeSitterAnalyzer {
    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CSharpAnalyzer.class);

    private static final LanguageSyntaxProfile CS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("class_declaration", "interface_declaration", "struct_declaration", "record_declaration", "record_struct_declaration"),
            Set.of("method_declaration", "constructor_declaration", "local_function_statement"),
            Set.of("field_declaration", "property_declaration", "event_field_declaration"),
            Set.of("attribute_list"),
            "name",
            "body",
            "parameters",
            "type",
            java.util.Map.of(
                "class.definition", SkeletonType.CLASS_LIKE,
                "function.definition", SkeletonType.FUNCTION_LIKE,
                "constructor.definition", SkeletonType.FUNCTION_LIKE,
                "field.definition", SkeletonType.FIELD_LIKE
            ),
            "",
            Set.of()
    );

    public CSharpAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, Language.C_SHARP, excludedFiles);
        log.debug("CSharpAnalyzer: Constructor called for project: {} with {} excluded files", project, excludedFiles.size());
    }

    public CSharpAnalyzer(IProject project) {
        this(project, Collections.emptySet());
        log.debug("CSharpAnalyzer: Constructor called for project: {}", project);
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterCSharp();
    }

    @Override
    protected String getQueryResource() {
        var resource = "treesitter/c_sharp.scm";
        log.trace("CSharpAnalyzer: getQueryResource() returning: {}", resource);
        return resource;
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String packageName,
                                      String classChain) {
        CodeUnit result;
        try {
            assert captureName != null : "Capture name cannot be null";
            result = switch (captureName) {
                case "class.definition" -> {
                    assert simpleName != null : "Simple name cannot be null for class definition";
                    String finalShortName = classChain == null || classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                    yield CodeUnit.cls(file, packageName, finalShortName);
                }
                case "function.definition" -> {
                    assert simpleName != null : "Simple name cannot be null for function definition";
                    assert classChain != null : "Class chain cannot be null for function definition";
                    String finalShortName = classChain + "." + simpleName;
                    yield CodeUnit.fn(file, packageName, finalShortName);
                }
                case "constructor.definition" -> {
                    assert classChain != null : "Class chain cannot be null for constructor definition";
                    String finalShortName = classChain + ".<init>";
                    yield CodeUnit.fn(file, packageName, finalShortName);
                }
                case "field.definition" -> {
                    assert simpleName != null : "Simple name cannot be null for field definition";
                    assert classChain != null : "Class chain cannot be null for field definition";
                    String finalShortName = classChain + "." + simpleName;
                    yield CodeUnit.field(file, packageName, finalShortName);
                }
                default -> {
                    log.warn("Unhandled capture name '{}' for name '{}' in file {}. Package: {}, class chain: {}",
                             captureName, simpleName, file, packageName, classChain);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn("Failed to create code unit for capture '{}' name '{}' (file {}): {}",
                     captureName, simpleName, file, e.getMessage(), e);
            return null;
        }
        if (log.isTraceEnabled()) {
            log.trace("Created code unit: {}", result != null ? result.toString() : "null");
        }
        return result;
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // C# query explicitly captures attributes/annotations to ignore them
        var ignored = Set.of("annotation");
        log.trace("CSharpAnalyzer: getIgnoredCaptures() returning: {}", ignored);
        return ignored;
    }

    @Override
    protected String bodyPlaceholder() {
        var placeholder = "{ … }";
        log.trace("CSharpAnalyzer: bodyPlaceholder() returning: {}", placeholder);
        return placeholder;
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix, String functionName, String paramsText, String returnTypeText, String indent) {
        assert funcNode != null : "Function node cannot be null";
        TSNode body = funcNode.getChildByFieldName("body");
        String signature;

        if (body != null && !body.isNull()) {
            assert src != null : "Source text cannot be null when rendering function declaration";
            signature = textSlice(funcNode.getStartByte(), body.getStartByte(), src).stripTrailing();
        } else {
            TSNode paramsNode = funcNode.getChildByFieldName("parameters");
            if (paramsNode != null && !paramsNode.isNull()) {
                 assert src != null : "Source text cannot be null when rendering function declaration";
                 signature = textSlice(funcNode.getStartByte(), paramsNode.getEndByte(), src).stripTrailing();
            } else {
                 assert src != null : "Source text cannot be null when rendering function declaration";
                 signature = textSlice(funcNode, src).lines().findFirst().orElse("").stripTrailing();
                 log.trace("renderFunctionDeclaration for C# (node type {}): body and params not found, using fallback signature '{}'", funcNode.getType(), signature);
            }
        }
        return signature + " " + bodyPlaceholder();
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        assert classNode != null : "Class node cannot be null";
        assert src != null : "Source text cannot be null when rendering class header";
        return signatureText + " {";
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        return cu.isClass() ? "}" : "";
    }

    @Override
    protected @Nullable String determinePackageName(@Nullable ProjectFile file, @Nullable TSNode definitionNode, @Nullable TSNode rootNode, @Nullable String src) {
        assert definitionNode != null : "Definition node cannot be null";
        assert rootNode != null : "Root node cannot be null";
        // C# namespaces are determined by traversing up from the definition node
        // to find enclosing namespace_declaration nodes.
        // The 'file' parameter is not used here as namespace is derived from AST content.
        java.util.List<String> namespaceParts = new java.util.ArrayList<>();
        TSNode current = definitionNode.getParent();

        while (current != null && !current.isNull() && !current.equals(rootNode)) {
            if ("namespace_declaration".equals(current.getType())) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
                    assert src != null : "Source text cannot be null when determining package name";
                    String nsPart = textSlice(nameNode, src);
                    assert nsPart != null : "Namespace part cannot be null";
                    namespaceParts.add(nsPart);
                }
            }
            current = current.getParent();
        }
        Collections.reverse(namespaceParts);
        return String.join(".", namespaceParts);
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return CS_SYNTAX_PROFILE;
    }
}
