package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterTypescript;

import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport; // For Spliterators

public final class TypescriptAnalyzer extends TreeSitterAnalyzer {
    private static final TSLanguage TS_LANGUAGE = new TreeSitterTypescript();

    private static final LanguageSyntaxProfile TS_SYNTAX_PROFILE = new LanguageSyntaxProfile(
            // classLikeNodeTypes
            Set.of("class_declaration", "interface_declaration", "enum_declaration", "abstract_class_declaration", "module"),
            // functionLikeNodeTypes
            Set.of("function_declaration", "method_definition", "arrow_function", "generator_function_declaration",
                   "function_signature", "method_signature"), // method_signature for interfaces
            // fieldLikeNodeTypes
            Set.of("variable_declarator", "public_field_definition", "property_signature", "enum_member"), // type_alias_declaration will be ALIAS_LIKE
            // decoratorNodeTypes
            Set.of("decorator"),
            // identifierFieldName
            "name",
            // bodyFieldName
            "body",
            // parametersFieldName
            "parameters",
            // returnTypeFieldName
            "return_type", // TypeScript has explicit return types
            // typeParametersFieldName
            "type_parameters", // Standard field name for type parameters in TS
            // captureConfiguration
            Map.of(
                "class.definition", SkeletonType.CLASS_LIKE,
                "interface.definition", SkeletonType.CLASS_LIKE,
                "enum.definition", SkeletonType.CLASS_LIKE,
                "module.definition", SkeletonType.CLASS_LIKE,
                "function.definition", SkeletonType.FUNCTION_LIKE,
                "field.definition", SkeletonType.FIELD_LIKE,
                "typealias.definition", SkeletonType.ALIAS_LIKE, // New mapping
                "decorator.definition", SkeletonType.UNSUPPORTED,
                "keyword.modifier", SkeletonType.UNSUPPORTED 
            ),
            // asyncKeywordNodeType
            "async", // TS uses 'async' keyword
            // modifierNodeTypes: Contains node types of keywords/constructs that act as modifiers.
            // Used in TreeSitterAnalyzer.buildSignatureString to gather modifiers by inspecting children.
            Set.of(
                "export", "default", "declare", "abstract", "static", "readonly",
                "accessibility_modifier", // for public, private, protected
                "async", "const", "let", "var", "override" // "override" might be via override_modifier
                // Note: "public", "private", "protected" themselves are not node types here,
                // but "accessibility_modifier" is the node type whose text content is one of these.
                // "const", "let" are token types for the `kind` of a lexical_declaration, often its first child.
                // "var" is a token type, often first child of variable_declaration.
            )
    );

    public TypescriptAnalyzer(IProject project, Set<String> excludedFiles) {
        super(project, excludedFiles);
    }

    public TypescriptAnalyzer(IProject project) {
        this(project, Collections.emptySet());
    }

    @Override
    protected TSLanguage getTSLanguage() {
        return TS_LANGUAGE;
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/typescript.scm";
    }

    @Override
    protected LanguageSyntaxProfile getLanguageSyntaxProfile() {
        return TS_SYNTAX_PROFILE;
    }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file,
                                      String captureName,
                                      String simpleName,
                                      String packageName,
                                      String classChain)
    {
        // Adjust FQN based on capture type and context
        String finalShortName;
        SkeletonType skeletonType = getSkeletonTypeForCapture(captureName);

        switch (skeletonType) {
            case CLASS_LIKE:
                finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                return CodeUnit.cls(file, packageName, finalShortName);
            case FUNCTION_LIKE:
                 if (simpleName.equals("anonymous_arrow_function") || simpleName.isEmpty()) {
                    log.warn("Anonymous or unnamed function found for capture {} in file {}. ClassChain: {}. Will use placeholder or rely on extracted name.", captureName, file, classChain);
                    // simpleName might be "anonymous_arrow_function" if #set! "default_name" was used and no var name found
                 }
                finalShortName = classChain.isEmpty() ? simpleName : classChain + "." + simpleName;
                return CodeUnit.fn(file, packageName, finalShortName);
            case FIELD_LIKE:
                finalShortName = classChain.isEmpty() ? "_module_." + simpleName : classChain + "." + simpleName;
                return CodeUnit.field(file, packageName, finalShortName);
            case ALIAS_LIKE:
                // Type aliases are top-level or module-level, treated like fields for FQN and CU type.
                finalShortName = classChain.isEmpty() ? "_module_." + simpleName : classChain + "." + simpleName;
                return CodeUnit.field(file, packageName, finalShortName);
            default: // UNSUPPORTED or other
                log.debug("Ignoring capture in TypescriptAnalyzer: {} (mapped to type {}) with name: {} and classChain: {}",
                          captureName, skeletonType, simpleName, classChain);
                return null;
        }
    }

    @Override
    protected String formatReturnType(TSNode returnTypeNode, String src) {
        if (returnTypeNode == null || returnTypeNode.isNull()) {
            return "";
        }
        String text = textSlice(returnTypeNode, src).strip();
        // A type_annotation node in TS is typically ": type"
        // We only want the "type" part for the suffix.
        if (text.startsWith(":")) {
            return text.substring(1).strip();
        }
        return text; // Should not happen if TS grammar for return_type capture is specific to type_annotation
    }

    @Override
    protected String determinePackageName(ProjectFile file, TSNode definitionNode, TSNode rootNode, String src) {
        // Initial implementation: directory-based, like JavaScript.
        // TODO: Enhance to detect 'namespace A.B.C {}' or 'module A.B.C {}' and use that.
        var projectRoot = getProject().getRoot();
        var filePath = file.absPath();
        var parentDir = filePath.getParent();

        if (parentDir == null || parentDir.equals(projectRoot)) {
            return ""; // File is in the project root
        }

        var relPath = projectRoot.relativize(parentDir);
        return relPath.toString().replace('/', '.').replace('\\', '.');
    }

    @Override
    protected String renderFunctionDeclaration(TSNode funcNode, String src,
                                               String exportAndModifierPrefix, String asyncPrefix, // Kept for signature compatibility, but ignored
                                               String functionName, String paramsText, String returnTypeText,
                                               String indent)
    {
        // exportAndModifierPrefix now contains all modifiers, including "async" if applicable.
        // The asyncPrefix parameter is kept for signature compatibility but ignored here.
        String combinedPrefix = exportAndModifierPrefix.stripTrailing(); // e.g., "export async", "public static"

        String tsReturnTypeSuffix = (returnTypeText != null && !returnTypeText.isEmpty()) ? ": " + returnTypeText.strip() : "";
        String signature;
        String bodySuffix;

        TSNode bodyNode = funcNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        boolean hasBody = bodyNode != null && !bodyNode.isNull() && bodyNode.getEndByte() > bodyNode.getStartByte();

        if ("arrow_function".equals(funcNode.getType())) {
            // combinedPrefix for arrow func (e.g. "export const")
            signature = String.format("%s %s%s%s =>", // Space after prefix if not empty
                                      combinedPrefix,
                                      functionName.isEmpty() ? "" : functionName,
                                      paramsText,
                                      tsReturnTypeSuffix).stripLeading(); // stripLeading in case prefix was empty
            bodySuffix = hasBody ? " " + bodyPlaceholder() : ";";
        } else {
            String keyword = "";
            if ("function_declaration".equals(funcNode.getType()) || "generator_function_declaration".equals(funcNode.getType())) {
                keyword = "function";
                if ("generator_function_declaration".equals(funcNode.getType())) keyword += "*";
            } else if ("constructor".equals(functionName) && "method_definition".equals(funcNode.getType())) {
                keyword = "constructor";
                functionName = ""; // constructor name is part of keyword
            } else if ("method_definition".equals(funcNode.getType())) {
                String nodeTextStart = textSlice(funcNode.getStartByte(), Math.min(funcNode.getEndByte(), funcNode.getStartByte() + 4), src);
                if (nodeTextStart.startsWith("get ")) {
                    keyword = "get";
                     // functionName will be the property name; ensure it's not duplicated if already part of `exportAndModifierPrefix`
                } else if (nodeTextStart.startsWith("set ")) {
                    keyword = "set";
                }
            }

            String endMarker = "";
            if (!hasBody && !"arrow_function".equals(funcNode.getType())) {
                endMarker = ";";
            }
            
            // Assemble: combinedPrefix [keyword] [functionName] paramsText returnTypeSuffix endMarker
            List<String> parts = new ArrayList<>();
            if (!combinedPrefix.isEmpty()) parts.add(combinedPrefix);
            if (!keyword.isEmpty()) parts.add(keyword);
            if (!functionName.isEmpty() || (keyword.equals("constructor") && functionName.isEmpty())) { // Add functionName unless it's a constructor where name is implied
                parts.add(functionName);
            }
            
            signature = String.join(" ", parts).strip() + paramsText + tsReturnTypeSuffix + endMarker;
            bodySuffix = hasBody ? " " + bodyPlaceholder() : "";
        }
        return indent + signature.strip() + bodySuffix;
    }

    @Override
    protected String formatFieldSignature(TSNode fieldNode, String src, String exportPrefix, String signatureText, String baseIndent, ProjectFile file) {
        String fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();
        // For TypeScript skeleton generation, don't add semicolon - the test expects clean signatures
        return baseIndent + fullSignature;
    }

    @Override
    protected String renderClassHeader(TSNode classNode, String src,
                                       String exportPrefix, // This now comes from captured @keyword.modifier list
                                       String signatureText, // This is the raw text slice from class start to body start
                                       String baseIndent)
    {
        String classKeyword;
        switch (classNode.getType()) {
            case "interface_declaration": classKeyword = "interface"; break;
            case "enum_declaration": classKeyword = "enum"; break;
            case "module": classKeyword = "namespace"; break;
            default: classKeyword = "class"; break;
        }

        String remainingSignature = signatureText.stripLeading();

        // Strip the comprehensive exportPrefix if it's at the start of the raw signature slice
        String strippedPrefix = exportPrefix.strip(); // e.g., "export abstract"
        if (!strippedPrefix.isEmpty() && remainingSignature.startsWith(strippedPrefix)) {
            remainingSignature = remainingSignature.substring(strippedPrefix.length()).stripLeading();
        }
        
        // Then, strip the class keyword itself
        if (remainingSignature.startsWith(classKeyword + " ")) {
            remainingSignature = remainingSignature.substring((classKeyword + " ").length()).stripLeading();
        } else if (remainingSignature.startsWith(classKeyword) && (remainingSignature.length() == classKeyword.length() || !Character.isLetterOrDigit(remainingSignature.charAt(classKeyword.length())))) {
             // Case where class keyword is not followed by space, e.g. "class<T>"
             remainingSignature = remainingSignature.substring(classKeyword.length()).stripLeading();
        }


        // remainingSignature is now "ClassName<Generics> extends Base implements Iface"
        // exportPrefix already has a trailing space if it's not empty.
        String finalPrefix = exportPrefix.stripTrailing();
        if (!finalPrefix.isEmpty() && !classKeyword.isEmpty() && !remainingSignature.isEmpty()) {
             finalPrefix += " ";
        }


        return baseIndent + finalPrefix + classKeyword + " " + remainingSignature + " {";
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, String src) {
        // TypeScript modifier extraction - look for export, declare, async, static, etc.
        // This method is called when keyword.modifier captures are not available.
        StringBuilder modifiers = new StringBuilder();
        
        // Check the node itself and its parent for common modifier patterns
        TSNode nodeToCheck = node;
        TSNode parent = node.getParent();
        
        // For variable declarators, check the parent declaration
        if ("variable_declarator".equals(node.getType()) && parent != null &&
            ("lexical_declaration".equals(parent.getType()) || "variable_declaration".equals(parent.getType()))) {
            nodeToCheck = parent;
        }
        
        // Check for export statement wrapper
        if (parent != null && "export_statement".equals(parent.getType())) {
            modifiers.append("export ");
            
            // Check for default export
            TSNode exportKeyword = parent.getChild(0);
            if (exportKeyword != null && parent.getChildCount() > 1) {
                TSNode defaultKeyword = parent.getChild(1);
                if (defaultKeyword != null && "default".equals(textSlice(defaultKeyword, src).strip())) {
                    modifiers.append("default ");
                }
            }
        }
        
        // Look for modifier keywords in the first few children of the declaration
        for (int i = 0; i < Math.min(nodeToCheck.getChildCount(), 5); i++) {
            TSNode child = nodeToCheck.getChild(i);
            if (child != null && !child.isNull()) {
                String childText = textSlice(child, src).strip();
                // Check for common TypeScript modifiers
                if (Set.of("declare", "abstract", "static", "readonly", "async", "const", "let", "var").contains(childText)) {
                    modifiers.append(childText).append(" ");
                } else if ("accessibility_modifier".equals(child.getType())) {
                    modifiers.append(childText).append(" ");
                }
            }
        }
        
        return modifiers.toString();
    }

    @Override
    protected String bodyPlaceholder() {
        return "{ ... }"; // TypeScript typically uses braces
    }

    @Override
    protected String getLanguageSpecificCloser(CodeUnit cu) {
        // Classes, interfaces, enums, modules/namespaces all use '}'
        if (cu.isClass()) { // isClass is true for all CLASS_LIKE CUs
            return "}";
        }
        return "";
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // e.g., @parameters, @return_type_node if they are only for context and not main definitions
        return Set.of("parameters", "return_type_node", "predefined_type_node", "type_identifier_node", "export.keyword");
    }
}
