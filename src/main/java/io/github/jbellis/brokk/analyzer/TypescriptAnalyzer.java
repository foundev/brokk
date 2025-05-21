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
            Set.of("variable_declarator", "public_field_definition", "property_signature", "enum_member"),
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
            // captureConfiguration
            Map.of(
                "class.definition", SkeletonType.CLASS_LIKE,
                "interface.definition", SkeletonType.CLASS_LIKE,
                "enum.definition", SkeletonType.CLASS_LIKE,
                "module.definition", SkeletonType.CLASS_LIKE, // Treat TS modules/namespaces like classes for CU
                "function.definition", SkeletonType.FUNCTION_LIKE,
                "field.definition", SkeletonType.FIELD_LIKE,
                "decorator.definition", SkeletonType.UNSUPPORTED // Decorators themselves are not CUs but modify others
            ),
            // asyncKeywordNodeType
            "async", // TS uses 'async' keyword
            // modifierNodeTypes
            Set.of("export_keyword", "declare_keyword", "abstract_keyword", "static_keyword", "readonly_keyword",
                   "public_keyword", "private_keyword", "protected_keyword") // specific keyword nodes for modifiers
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
        return switch (captureName) {
            case "class.definition", "interface.definition", "enum.definition", "module.definition" -> {
                String finalShortName = classChain.isEmpty() ? simpleName : classChain + "$" + simpleName;
                yield CodeUnit.cls(file, packageName, finalShortName);
            }
            case "function.definition" -> {
                String finalShortName;
                 // For arrow functions assigned to const/let, simpleName might be "anonymous_arrow_function" if not extracted from var.
                 // The TreeSitterAnalyzer's simpleName extraction tries hard to get it from the variable_declarator's name.
                if (simpleName.equals("anonymous_arrow_function") || simpleName.isEmpty()) {
                    // This case should be rare if the query and name extraction are robust for assigned arrow functions.
                    // Consider if a more unique name is needed or if such CUs should be skipped.
                    // For now, let's try to make it somewhat unique or log.
                    log.warn("Anonymous or unnamed function found for capture {} in file {}. ClassChain: {}", captureName, file, classChain);
                    // Potentially skip by returning null if truly anonymous and not desired.
                    // For now, proceed with a placeholder if needed, or rely on simpleName being correctly extracted.
                }

                if (!classChain.isEmpty()) { // Method within a class/interface/module or function within a module
                    finalShortName = classChain + "." + simpleName;
                } else { // Top-level function in the file
                    finalShortName = simpleName;
                }
                yield CodeUnit.fn(file, packageName, finalShortName);
            }
            case "field.definition" -> {
                String finalShortName;
                if (classChain.isEmpty()) { // Top-level variable
                    finalShortName = "_module_." + simpleName;
                } else { // Class/interface/enum field
                    finalShortName = classChain + "." + simpleName;
                }
                yield CodeUnit.field(file, packageName, finalShortName);
            }
            default -> {
                log.debug("Ignoring capture in TypescriptAnalyzer: {} with name: {} and classChain: {}", captureName, simpleName, classChain);
                yield null;
            }
        };
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
                                               String exportPrefix, String asyncPrefix,
                                               String functionName, String paramsText, String returnTypeText,
                                               String indent)
    {
        String tsReturnTypeSuffix = (returnTypeText != null && !returnTypeText.isEmpty()) ? ": " + returnTypeText.strip() : "";
        String signature;
        String bodySuffix;

        // Check if body is null or empty (e.g. for interface methods, abstract methods, function_signatures)
        TSNode bodyNode = funcNode.getChildByFieldName(getLanguageSyntaxProfile().bodyFieldName());
        boolean hasBody = bodyNode != null && !bodyNode.isNull() && bodyNode.getEndByte() > bodyNode.getStartByte();

        boolean requiresBodyAssert = switch (funcNode.getType()) {
            case "function_declaration", "method_definition", "arrow_function", "generator_function_declaration" -> true;
            default -> false; // e.g. function_signature, method_signature in interfaces
        };

        if (requiresBodyAssert && !hasBody) {
            assert false : "Function type " + funcNode.getType() + " (name: " + functionName + ") requires a body, but none was found or body is empty. Node text: " + textSlice(funcNode, src).lines().findFirst().orElse("");
        }

        if ("arrow_function".equals(funcNode.getType())) {
            signature = String.format("%s%s%s%s =>",
                                      exportPrefix + asyncPrefix, // Modifiers first
                                      functionName.isEmpty() ? "" : functionName,
                                      paramsText,
                                      tsReturnTypeSuffix);
            bodySuffix = hasBody ? " " + bodyPlaceholder() : "";
        } else { // Covers function_declaration, method_definition, function_signature, method_signature
            String keyword = "function";
            // method_signature or function_signature are typically found in interfaces or type aliases and don't use the "function" keyword.
            if ("method_signature".equals(funcNode.getType()) || "function_signature".equals(funcNode.getType())) {
                keyword = "";
            }
             // For constructors, the name from query might be "constructor"
            if ("constructor".equals(functionName) && "method_definition".equals(funcNode.getType())) {
                keyword = ""; // Output: "constructor(...)" not "function constructor(...)"
            }

            String endMarker = ";"; // Default for bodiless signatures
            if (hasBody || ("method_definition".equals(funcNode.getType()) || "function_declaration".equals(funcNode.getType())) && !keyword.isEmpty()) {
                // If it has a body, or it's a standard method/function definition (which implies a body or {}), no semicolon needed here.
                endMarker = "";
            }


            signature = String.format("%s%s%s%s%s%s",
                                      exportPrefix + asyncPrefix,
                                      keyword.isEmpty() ? "" : keyword + " ",
                                      functionName,
                                      paramsText,
                                      tsReturnTypeSuffix,
                                      endMarker
                                      );
            bodySuffix = (hasBody && (!keyword.isEmpty() || "constructor".equals(functionName))) ? " " + bodyPlaceholder() : "";
        }
        return indent + signature.stripTrailing() + bodySuffix;
    }

    // getModifierKeywords and getVisibilityPrefix are defined above the renderClassHeader method

    @Override
    protected String renderClassHeader(TSNode classNode, String src,
                                       String exportPrefix,
                                       String signatureText,
                                       String baseIndent)
    {
        String processedSignatureText = signatureText.stripLeading();
        // exportPrefix (from getVisibilityPrefix) now contains all relevant modifiers in order.
        // We need to remove these from the beginning of signatureText if they are duplicated.
        String[] prefixWords = exportPrefix.strip().split("\\s+"); // split actual words in prefix
        for (String word : prefixWords) {
            if (!word.isEmpty() && processedSignatureText.startsWith(word)) {
                processedSignatureText = processedSignatureText.substring(word.length()).stripLeading();
            }
        }
        String finalPrefixString = exportPrefix.stripTrailing();
        return baseIndent + finalPrefixString + (finalPrefixString.isEmpty() ? "" : " ") + processedSignatureText + " {";
    }

    private List<String> getModifierKeywords(TSNode definitionNode, String src) {
        Set<String> keywords = new java.util.LinkedHashSet<>(); // Preserves insertion order, helps with `export default`

        TSNode parent = definitionNode.getParent();
        TSNode grandparent = (parent != null && !parent.isNull()) ? parent.getParent() : null;

        // Case 1: `export [default] const/let/var ...`
        // export_statement -> lexical_declaration -> variable_declarator (definitionNode)
        if ("variable_declarator".equals(definitionNode.getType()) &&
            parent != null && ("lexical_declaration".equals(parent.getType()) || "variable_declaration".equals(parent.getType())) &&
            grandparent != null && "export_statement".equals(grandparent.getType())) {
            keywords.add("export");
            for (int i = 0; i < grandparent.getChildCount(); i++) {
                if ("default_keyword".equals(grandparent.getChild(i).getType())) {
                    keywords.add("default");
                    break;
                }
            }
        }
        // Case 2: `export [default] function/class/interface/enum/module ...`
        // export_statement -> actual_declaration (definitionNode)
        else if (parent != null && "export_statement".equals(parent.getType())) {
            keywords.add("export");
            for (int i = 0; i < parent.getChildCount(); i++) {
                if ("default_keyword".equals(parent.getChild(i).getType())) {
                    keywords.add("default");
                    break;
                }
            }
        }

        // Add modifiers from the definition node itself (e.g., `public static readonly` on a class field, or `export` if directly on class/func)
        TSNode modifiersNodeOnDef = definitionNode.getChildByFieldName("modifiers");
        if (modifiersNodeOnDef != null && !modifiersNodeOnDef.isNull()) {
            for (int i = 0; i < modifiersNodeOnDef.getChildCount(); i++) {
                TSNode modChild = modifiersNodeOnDef.getChild(i);
                if (getLanguageSyntaxProfile().modifierNodeTypes().contains(modChild.getType())) {
                    String modText = textSlice(modChild, src).strip();
                    if (modText.equals("export") && keywords.contains("export")) continue;
                    if (modText.equals("default") && keywords.contains("default")) continue;
                    keywords.add(modText);
                }
            }
        }

        // Add `const/let/var` if definitionNode is `variable_declarator`
        if ("variable_declarator".equals(definitionNode.getType())) {
            TSNode lexicalOrVarDecl = parent; // parent of variable_declarator
            if (lexicalOrVarDecl != null && !lexicalOrVarDecl.isNull() &&
                ("lexical_declaration".equals(lexicalOrVarDecl.getType()) || "variable_declaration".equals(lexicalOrVarDecl.getType()))) {
                TSNode kindNode = lexicalOrVarDecl.getChild(0); // const, let, var
                if (kindNode != null && !kindNode.isNull()) {
                    keywords.add(textSlice(kindNode, src).strip());
                }
            }
        }
        
        // Order the collected keywords:
        List<String> orderedKeywords = new ArrayList<>();
        // Standard order: export, default, abstract, static, visibility, readonly, kind
        if (keywords.contains("export")) orderedKeywords.add("export");
        if (keywords.contains("default")) orderedKeywords.add("default");
        if (keywords.contains("abstract")) orderedKeywords.add("abstract");
        if (keywords.contains("static")) orderedKeywords.add("static");
        // Visibility: only one of public, protected, private
        if (keywords.contains("public")) orderedKeywords.add("public");
        else if (keywords.contains("protected")) orderedKeywords.add("protected");
        else if (keywords.contains("private")) orderedKeywords.add("private");
        if (keywords.contains("readonly")) orderedKeywords.add("readonly");

        // Kind: const, let, var. Only one should be present.
        List.of("const", "let", "var").stream()
            .filter(keywords::contains)
            .findFirst()
            .ifPresent(orderedKeywords::add);

        return orderedKeywords;
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, String src) {
        List<String> modifiers = getModifierKeywords(node, src);
        if (modifiers.isEmpty()) {
            return "";
        }
        return String.join(" ", modifiers) + " ";
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
