package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterTypescript;

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

        if ("arrow_function".equals(funcNode.getType())) {
            signature = String.format("%s%s%s%s =>",
                                      exportPrefix + asyncPrefix, // Modifiers first
                                      functionName.isEmpty() ? "" : functionName, // name might be empty for some direct arrow calls
                                      paramsText,
                                      tsReturnTypeSuffix);
            bodySuffix = hasBody ? " " + bodyPlaceholder() : ""; // or specific arrow body like " x" if simple
        } else { // Covers function_declaration, method_definition, function_signature, method_signature
            String keyword = "function";
            if ("method_signature".equals(funcNode.getType()) || "function_signature".equals(funcNode.getType())) {
                keyword = ""; // No "function" keyword for interface method signatures
            }
             // For constructors, the name from query might be "constructor"
            if ("constructor".equals(functionName) && "method_definition".equals(funcNode.getType())) {
                keyword = ""; // "constructor(...)" not "function constructor(...)"
            }

            signature = String.format("%s%s%s%s%s%s",
                                      exportPrefix + asyncPrefix,
                                      keyword.isEmpty() ? "" : keyword + " ",
                                      functionName,
                                      paramsText,
                                      tsReturnTypeSuffix,
                                      (hasBody || "method_definition".equals(funcNode.getType()) || "function_declaration".equals(funcNode.getType())) && !keyword.isEmpty() ? "" : ";"
                                      // Add ";" for bodiless signatures like in interfaces, unless it's a regular func/method def that expects a body
                                      );
             bodySuffix = (hasBody && !keyword.isEmpty()) ? " " + bodyPlaceholder() : "";
             if (("method_signature".equals(funcNode.getType()) || "function_signature".equals(funcNode.getType())) && !signature.endsWith(";")) {
                 signature += ";"; // Ensure interface/type methods end with a semicolon
             }
        }
        return indent + signature + bodySuffix;
    }


    @Override
    protected String renderClassHeader(TSNode classNode, String src,
                                       String exportPrefix, // This comes from getVisibilityPrefix or context
                                       String signatureText, // This is textSlice(nodeStart, bodyStart)
                                       String baseIndent)
    {
        // signatureText is the part like "class MyClass<T> extends Base implements IBase"
        // exportPrefix is already determined.
        // We might need to add abstract keyword if present.
        String abstractPrefix = "";
        TSNode modifiersNode = classNode.getChildByFieldName("modifiers"); // Assuming 'modifiers' field exists
        if (modifiersNode != null && !modifiersNode.isNull()) {
            for (int i = 0; i < modifiersNode.getChildCount(); i++) {
                TSNode modifierChild = modifiersNode.getChild(i);
                if ("abstract_keyword".equals(modifierChild.getType())) {
                    abstractPrefix = "abstract ";
                    break;
                }
            }
        } else if ("abstract_class_declaration".equals(classNode.getType())) {
            // For abstract_class_declaration, the "abstract" is part of the node type,
            // signatureText might already include it. If not, add it.
            if (!signatureText.stripLeading().startsWith("abstract")) {
                 abstractPrefix = "abstract ";
            }
        }


        String fullPrefix = exportPrefix + abstractPrefix;
        // Avoid double "export abstract" if signatureText already contains one of them due to broad text slicing
        String cleanSignatureText = signatureText;
        if (fullPrefix.contains("export") && cleanSignatureText.stripLeading().startsWith("export")) {
            cleanSignatureText = cleanSignatureText.replaceFirst("export\\s*", "").stripLeading();
        }
        if (fullPrefix.contains("abstract") && cleanSignatureText.stripLeading().startsWith("abstract")) {
             cleanSignatureText = cleanSignatureText.replaceFirst("abstract\\s*", "").stripLeading();
        }

        return baseIndent + fullPrefix + cleanSignatureText.strip() + " {";
    }


    @Override
    protected String getVisibilityPrefix(TSNode node, String src) {
        StringBuilder prefix = new StringBuilder();

        // 1. Check for explicit 'export' if node is child of 'export_statement'
        TSNode parent = node.getParent();
        if (parent != null && !parent.isNull() && "export_statement".equals(parent.getType())) {
            // Check if it's `export default`
            boolean isDefaultExport = false;
            for (int i = 0; i < parent.getChildCount(); i++) {
                TSNode child = parent.getChild(i);
                if ("default_keyword".equals(child.getType())) {
                    isDefaultExport = true;
                    break;
                }
            }
            prefix.append("export ");
            if (isDefaultExport) {
                prefix.append("default ");
            }
        }

        // 2. Check for modifiers on the node itself (e.g. public, private, static, export keyword as modifier)
        // Tree-sitter TS grammar often has a 'modifiers' child node.
        TSNode modifiersNode = node.getChildByFieldName("modifiers");
        if (modifiersNode == null || modifiersNode.isNull()) { // Fallback for nodes like lexical_declaration which may not have 'modifiers' field
            if ("lexical_declaration".equals(node.getType()) || "variable_declaration".equals(node.getType())) {
                 // Parent might be export_statement, handled above.
                 // Here, we check first child of lexical_declaration if it's export_keyword (less common directly)
                TSNode firstChild = node.getChild(0);
                if (firstChild != null && !firstChild.isNull() && "export_keyword".equals(firstChild.getType())) {
                    if (!prefix.toString().contains("export ")) { // Avoid double "export"
                         prefix.append("export ");
                    }
                }
            }
        }


        if (modifiersNode != null && !modifiersNode.isNull()) {
            // TSNode does not have a direct children() method. Iterate using getChildCount and getChild.
            List<TSNode> modifierChildren = new java.util.ArrayList<>();
            for (int i = 0; i < modifiersNode.getChildCount(); i++) {
                modifierChildren.add(modifiersNode.getChild(i));
            }

            String collectedModifiers = modifierChildren.stream()
                .map(modNode -> {
                    String type = modNode.getType();
                    if (getLanguageSyntaxProfile().modifierNodeTypes().contains(type)) {
                        // For "export_keyword", only add "export" if not already added by parent check
                        if (type.equals("export_keyword") && prefix.toString().contains("export ")) {
                            return "";
                        }
                        return textSlice(modNode, src).strip();
                    }
                    return "";
                })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" "));

            if (!collectedModifiers.isEmpty()) {
                // Ensure export from modifiers list doesn't duplicate one from parent export_statement
                if (collectedModifiers.startsWith("export") && prefix.toString().contains("export ")) {
                    String tempCollected = collectedModifiers.replaceFirst("export\\s*", "").strip();
                    if (!prefix.toString().endsWith(" ")) prefix.append(" ");
                    prefix.append(tempCollected);
                    if (!tempCollected.isEmpty() && !prefix.toString().endsWith(" ")) prefix.append(" ");

                } else {
                    if (!prefix.toString().endsWith(" ") && !collectedModifiers.startsWith(" ")) prefix.append(" ");
                    prefix.append(collectedModifiers);
                    if (!prefix.toString().endsWith(" ")) prefix.append(" ");
                }
            }
        }


        // For variable_declarator, the 'export' or 'const/let/var' is handled by its parent lexical_declaration.
        // The getVisibilityPrefix is called on the actual *definition node* from the SCM query.
        // If @field.definition is variable_declarator, its parent (lexical_declaration) might have modifiers.
        if ("variable_declarator".equals(node.getType())) {
            TSNode lexicalOrVarDecl = node.getParent();
            if (lexicalOrVarDecl != null && !lexicalOrVarDecl.isNull() &&
                ("lexical_declaration".equals(lexicalOrVarDecl.getType()) || "variable_declaration".equals(lexicalOrVarDecl.getType())))
            {
                // Visibility like export handled by lexicalOrVarDecl's parent (export_statement) or its modifiers.
                // Here we add const/let/var.
                String keyword = textSlice(lexicalOrVarDecl.getChild(0), src).strip(); // const, let, var
                if (!keyword.isEmpty()) {
                    // Prepend keyword if not already there (e.g. from a broader prefix logic)
                    if (!prefix.toString().contains(keyword)) {
                        prefix.insert(0, keyword + " ");
                    }
                }
            }
        }
        return prefix.toString().stripTrailing(); // Ensure no trailing space if it's the only thing
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
