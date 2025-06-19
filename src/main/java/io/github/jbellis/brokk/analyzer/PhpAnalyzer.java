package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.jetbrains.annotations.Nullable;
import org.treesitter.*;
import java.util.stream.Stream;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class PhpAnalyzer extends TreeSitterAnalyzer {
    // PHP_LANGUAGE field removed, createTSLanguage will provide new instances.

    private static final String NODE_TYPE_NAMESPACE_DEFINITION = "namespace_definition";
    private static final String NODE_TYPE_PHP_TAG = "php_tag";
    private static final String NODE_TYPE_DECLARE_STATEMENT = "declare_statement";
    private static final String NODE_TYPE_COMPOUND_STATEMENT = "compound_statement";
    private static final String NODE_TYPE_REFERENCE_MODIFIER = "reference_modifier";
    private static final String NODE_TYPE_READONLY_MODIFIER = "readonly_modifier";


    private static final LanguageSyntaxProfile PHP_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            Set.of("class_declaration", "interface_declaration", "trait_declaration"), // classLikeNodeTypes
            Set.of("function_definition", "method_declaration"),                     // functionLikeNodeTypes
            Set.of("property_declaration", "const_declaration"),                     // fieldLikeNodeTypes (capturing the whole declaration)
            Set.of("attribute_list"),                                                // decoratorNodeTypes (PHP attributes are grouped in attribute_list)
            "name",                                                                  // identifierFieldName
            "body",                                                                  // bodyFieldName (applies to functions/methods, class body is declaration_list)
            "parameters",                                                            // parametersFieldName
            "return_type",                                                           // returnTypeFieldName (for return type declaration)
            java.util.Map.of(                                                        // captureConfiguration
                    "class.definition", SkeletonType.CLASS_LIKE,
                    "interface.definition", SkeletonType.CLASS_LIKE,
                    "trait.definition", SkeletonType.CLASS_LIKE,
                    "function.definition", SkeletonType.FUNCTION_LIKE,
                    "field.definition", SkeletonType.FIELD_LIKE,
                    "attribute.definition", SkeletonType.UNSUPPORTED // Attributes are handled by getPrecedingDecorators
            ),
            "",                                                                      // asyncKeywordNodeType (PHP has no async/await keywords for functions)
            Set.of("visibility_modifier", "static_modifier", "abstract_modifier", "final_modifier", NODE_TYPE_READONLY_MODIFIER) // modifierNodeTypes
    );

    private final Map<ProjectFile, String> fileScopedPackageNames = new ConcurrentHashMap<>();
    private final ThreadLocal<TSQuery> phpNamespaceQuery;


    public PhpAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, Language.PHP, excludedFiles);
        // Initialize the ThreadLocal for the PHP namespace query.
        // getTSLanguage() is safe to call here.
        this.phpNamespaceQuery = ThreadLocal.withInitial(() -> {
            try {
                return new TSQuery(getTSLanguage(), "(namespace_definition name: (namespace_name) @nsname)");
            } catch (Exception e) { // TSQuery constructor can throw various exceptions
                log.error("Failed to compile phpNamespaceQuery for PhpAnalyzer ThreadLocal", e);
                throw e; // Re-throw to indicate critical setup error for this thread's query
            }
        });
    }

    public PhpAnalyzer(IProject project) {
        this(project, Collections.emptySet());
    }

    @Override
    protected TSLanguage createTSLanguage() {
        return new TreeSitterPhp();
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/php.scm";
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return PHP_SYNTAX_PROFILE;
    }

    @Override
    protected @Nullable CodeUnit createCodeUnit(ProjectFile file,
                                              String captureName, 
                                              String simpleName,
                                              String packageName,
                                              String classChain)
    {
        return switch (captureName) {
            case "class.definition", "interface.definition", "trait.definition" -> {
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                yield CodeUnit.cls(file, packageName, finalShortName);
            }
            case "function.definition" -> { // Covers global functions and class methods
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                yield CodeUnit.fn(file, packageName, finalShortName);
            }
            case "field.definition" -> { // Covers class properties, class constants, and global constants
                String finalShortName;
                if (classChain.isEmpty()) { // Global constant
                    finalShortName = "_module_." + simpleName;
                } else { // Class property or class constant
                    finalShortName = classChain + "." + simpleName;
                }
                yield CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                // Attributes are handled by decorator logic, not direct CUs.
                // Namespace definitions are used by determinePackageName.
                // The "namespace.name" capture from the main query is now part of getIgnoredCaptures
                // as namespace processing is handled by computeFilePackageName with its own query.
                if (!"attribute.definition".equals(captureName) &&
                    !"namespace.definition".equals(captureName) && // Main query's namespace.definition
                    !"namespace.name".equals(captureName)) {       // Main query's namespace.name
                     log.debug("Ignoring capture in PhpAnalyzer: {} with name: {} and classChain: {}", captureName, simpleName, classChain);
                }
                yield null;
            }
        };
    }

    private String computeFilePackageName(TSNode rootNode, String src) {
        TSQuery currentPhpNamespaceQuery = this.phpNamespaceQuery.get();

        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(currentPhpNamespaceQuery, rootNode);
        TSQueryMatch match = new TSQueryMatch(); // Reusable match object

        if (cursor.nextMatch(match)) { // Assuming one namespace per file, take the first
            for (TSQueryCapture capture : match.getCaptures()) {
                // Check capture name using query's method
                if ("nsname".equals(currentPhpNamespaceQuery.getCaptureNameForId(capture.getIndex()))) {
                    return textSlice(capture.getNode(), src).replace('\\', '.');
                }
            }
        }
        // Fallback to manual scan if query fails or no match, though query is preferred
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            TSNode current = rootNode.getChild(i);
            if (NODE_TYPE_NAMESPACE_DEFINITION.equals(current.getType())) {
                return textSlice(current.getChildByFieldName("name"), src).replace('\\', '.');
            }
            if (
                !NODE_TYPE_PHP_TAG.equals(current.getType()) &&
                !NODE_TYPE_NAMESPACE_DEFINITION.equals(current.getType()) &&
                !NODE_TYPE_DECLARE_STATEMENT.equals(current.getType()) && i > 5) {
                break; // Stop searching after a few top-level elements
            }
        }
        return ""; // No namespace found
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        // definitionNode is not used here as package is file-scoped
        return fileScopedPackageNames.computeIfAbsent(file, f -> computeFilePackageName(rootNode, src));
    }


    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        if (cu.isClass()) { // CodeUnit.cls is used for class, interface, trait
            boolean isEmptyCuBody = childrenByParent.getOrDefault(cu, List.of()).isEmpty();
            if (isEmptyCuBody) {
                return ""; // Closer already handled by renderClassHeader for empty bodies
            }
            return "}";
        }
        return "";
    }

    @Override
    protected String getLanguageSpecificIndent() {
        return "  ";
    }

    private String extractModifiers(TSNode methodNode, String src) {
        return Stream.iterate(0, i -> i < methodNode.getChildCount(), i -> i + 1)
                   .map(methodNode::getChild)
                   .takeWhile(child -> !child.getType().equals("function"))
                   .filter(child -> !child.isNull())
                   .map(child -> {
                       String type = child.getType();
                       if (PHP_SYNTAX_PROFILE.decoratorNodeTypes().contains(type)) {
                           return textSlice(child, src) + "\n";
                       } else if (PHP_SYNTAX_PROFILE.modifierNodeTypes().contains(type)) {
                           return textSlice(child, src) + " ";
                       }
                       return "";
                   })
                   .collect(Collectors.joining());
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        TSNode bodyNode = classNode.getChildByFieldName(PHP_SYNTAX_PROFILE.bodyFieldName());
        boolean isEmptyBody = bodyNode.getNamedChildCount() == 0;
        String suffix = isEmptyBody ? " { }" : " {";
        
        return signatureText.stripTrailing() + suffix;
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src, String exportPrefix, String asyncPrefix,
                                             String functionName, String paramsText, String returnTypeText, String indent) {
        // Attributes that are children of the funcNode (e.g., PHP attributes on methods)
        // are collected by extractModifiers.
        // exportPrefix and asyncPrefix are "" for PHP. indent is also "" at this stage from base.
        String modifiers = extractModifiers(funcNode, src);
        
        String ampersand = Stream.iterate(0, i -> i < funcNode.getChildCount(), i -> i + 1)
                               .map(funcNode::getChild)
                               .filter(child -> NODE_TYPE_REFERENCE_MODIFIER.equals(child.getType()))
                               .findFirst()
                               .map(child -> textSlice(child, src).trim())
                               .orElse("");

        String formattedReturnType = Optional.ofNullable(returnTypeText)
                                           .filter(rt -> !rt.isEmpty())
                                           .map(rt -> ": " + rt.strip())
                                           .orElse("");

        String fnSignature = String.format("%sfunction %s%s%s%s",
                                  modifiers,
                                  ampersand, 
                                  functionName,
                                  paramsText, 
                                  formattedReturnType).stripTrailing();
        
        TSNode bodyNode = funcNode.getChildByFieldName(PHP_SYNTAX_PROFILE.bodyFieldName());
        return NODE_TYPE_COMPOUND_STATEMENT.equals(bodyNode.getType())
            ? fnSignature + " { " + bodyPlaceholder() + " }"
            : fnSignature + ";";
    }

    @Override
    protected String bodyPlaceholder() {
        return "...";
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, String src) {
        return "";
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // namespace.definition and namespace.name from the main query are ignored
        // as namespace processing is now handled by computeFilePackageName.
        // attribute.definition is handled by decorator logic in base class.
        return Set.of("namespace.definition", "namespace.name", "attribute.definition");
    }
}
