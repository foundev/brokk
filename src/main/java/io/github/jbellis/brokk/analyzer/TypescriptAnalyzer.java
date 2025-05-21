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
                "module.definition", SkeletonType.CLASS_LIKE,
                "function.definition", SkeletonType.FUNCTION_LIKE,
                // "method.definition" is covered by "function.definition" due to query structure
                // "arrow_function.definition" is covered by "function.definition"
                "field.definition", SkeletonType.FIELD_LIKE,
                // "property.definition" (class/interface property) is covered by "field.definition"
                // "enum_member.definition" is covered by "field.definition"
                "decorator.definition", SkeletonType.UNSUPPORTED
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
            // This assertion is too strict as some queries might yield method_definition for abstract methods too
            // For now, we rely on hasBody to correctly append placeholder or semicolon.
            log.debug("Function type {} (name: {}) requires a body for placeholder, but none was found or body is empty. Node text: {}",
                     funcNode.getType(), functionName, textSlice(funcNode, src).lines().findFirst().orElse(""));
        }

        if ("arrow_function".equals(funcNode.getType())) {
            // exportPrefix for arrow func comes from its variable_declarator's context
            signature = String.format("%s%s%s%s =>",
                                      exportPrefix.stripTrailing() + asyncPrefix, // Modifiers first
                                      functionName.isEmpty() ? "" : functionName, // functionName for arrow is var name
                                      paramsText,
                                      tsReturnTypeSuffix);
            bodySuffix = hasBody ? " " + bodyPlaceholder() : ";"; // Arrow func type implies expression or block, always has a "body"
                                                                  // but if it's just a type signature for an arrow func var, maybe it ends in ; ?
                                                                  // The query should only capture arrow functions with bodies.
                                                                  // Let's assume if hasBody is true, placeholder. If somehow not, this path might be wrong.
                                                                  // Test cases will clarify. For `const anArrowFunc = (msg: string): void => { ... }`, hasBody is true.
        } else { // Covers function_declaration, method_definition, function_signature, method_signature
            String keyword = ""; // Default for methods/signatures inside class/interface
            if ("function_declaration".equals(funcNode.getType()) || "generator_function_declaration".equals(funcNode.getType())) {
                keyword = "function"; // Top-level functions or explicitly 'function foo()'
                 if ("generator_function_declaration".equals(funcNode.getType())) keyword += "*";
            } else if ("constructor".equals(functionName) && "method_definition".equals(funcNode.getType())) {
                keyword = "constructor"; // Special keyword for constructor, name is "constructor"
                functionName = ""; // constructor name is part of keyword
            } else if ("method_definition".equals(funcNode.getType())) {
                 // For get/set accessors, the "name" is the property name, and "get" or "set" is a modifier/keyword part of the definition.
                 // The query captures "get" or "set" text as part of method_definition node.
                 // We need to check if funcNode starts with "get " or "set "
                 String nodeText = textSlice(funcNode.getStartByte(), funcNode.getStartByte() + 4, src); // Check first few chars
                 if (nodeText.startsWith("get ")) {
                     keyword = "get";
                     functionName = functionName.startsWith("get ") ? functionName.substring(4) : functionName;
                 } else if (nodeText.startsWith("set ")) {
                     keyword = "set";
                     functionName = functionName.startsWith("set ") ? functionName.substring(4) : functionName;
                 }
                 // Otherwise, it's a normal method, no explicit "function" keyword.
            }
            // For function_signature, method_signature, no keyword.

            String endMarker = "";
            if (!hasBody && !"arrow_function".equals(funcNode.getType())) { // If no body (interface, abstract, overload)
                endMarker = ";";
            }

            signature = String.format("%s%s%s%s%s%s",
                                      exportPrefix.stripTrailing() + asyncPrefix,
                                      keyword.isEmpty() ? "" : keyword + (functionName.isEmpty() && keyword.equals("constructor") ? "" : " "), // Add space if keyword exists and it's not just "constructor"
                                      functionName,
                                      paramsText,
                                      tsReturnTypeSuffix,
                                      endMarker);
            bodySuffix = hasBody ? " " + bodyPlaceholder() : "";
        }
        return indent + signature.stripTrailing() + bodySuffix;
    }

    @Override
    protected String formatFieldSignature(TSNode fieldNode, String src, String exportPrefix, String signatureText, String baseIndent) {
        String fullSignature = (exportPrefix.stripTrailing() + " " + signatureText.strip()).strip();
        // Avoid adding semicolon if signature already ends with it, or if it's a complex initializer like an object or function body placeholder
        if (!fullSignature.endsWith(";") &&
            !fullSignature.endsWith("}") && // common for object literal assignments
            !fullSignature.endsWith(bodyPlaceholder().trim())) { // check for function assignments rendered with placeholder
            fullSignature += ";";
        }
        return baseIndent + fullSignature;
    }


    // getModifierKeywords and getVisibilityPrefix are defined above the renderClassHeader method

    @Override
    protected String renderClassHeader(TSNode classNode, String src,
                                       String exportPrefix, // This now comes from getVisibilityPrefix, includes all mods
                                       String signatureText, // This is the raw text slice from class start to body start
                                       String baseIndent)
    {
        String processedSignatureText = signatureText.stripLeading();
        String classKeyword;
        switch (classNode.getType()) {
            case "interface_declaration": classKeyword = "interface"; break;
            case "enum_declaration": classKeyword = "enum"; break;
            case "module": classKeyword = "namespace"; break; // Or "module", but "namespace" is more common in modern TS skeletons
            default: classKeyword = "class"; break; // class_declaration, abstract_class_declaration
        }

        // The signatureText from textSlice might contain modifiers already.
        // exportPrefix from getVisibilityPrefix should be the source of truth for modifiers.
        // We need to strip these known modifiers + classKeyword from processedSignatureText if present,
        // then prepend the controlled exportPrefix and classKeyword.

        String tempSig = processedSignatureText;
        // Strip export/default if present in tempSig, as exportPrefix handles it
        if (tempSig.startsWith("export default ")) tempSig = tempSig.substring("export default ".length()).stripLeading();
        else if (tempSig.startsWith("export ")) tempSig = tempSig.substring("export ".length()).stripLeading();
        // Strip abstract if present
        if (tempSig.startsWith("abstract ")) tempSig = tempSig.substring("abstract ".length()).stripLeading();
        // Strip the class keyword itself
        if (tempSig.startsWith(classKeyword + " ")) tempSig = tempSig.substring((classKeyword + " ").length()).stripLeading();
        
        // tempSig now should be "ClassName<Generics> extends Base implements Iface"
        processedSignatureText = tempSig;

        String finalPrefix = exportPrefix.stripTrailing(); // exportPrefix already has a trailing space if not empty
        if (!finalPrefix.isEmpty()) finalPrefix += " ";

        return baseIndent + finalPrefix + classKeyword + " " + processedSignatureText + " {";
    }

    private List<String> getModifierKeywords(TSNode definitionNode, String src) {
        Set<String> keywords = new java.util.LinkedHashSet<>(); // Preserves insertion order, helps with `export default`

        TSNode parent = definitionNode.getParent();
        TSNode ancestor = parent; // Start with parent for typical export_statement -> decl

        // Check for export context:
        // export [default] class/func/interface/enum/module/type (definitionNode is the decl)
        // export [default] const/let/var (definitionNode is variable_declarator, parent is lexical/var_decl, grandparent is export_stmt)
        if (parent != null && "export_statement".equals(parent.getType())) { // Direct export: export class X {}, export function f() {}
            keywords.add("export");
            for (int i = 0; i < parent.getChildCount(); i++) {
                TSNode child = parent.getChild(i);
                if ("default".equals(child.getType())) { // Check for "default" keyword node type
                    keywords.add("default");
                    break;
                }
            }
        } else if (parent != null && ("lexical_declaration".equals(parent.getType()) || "variable_declaration".equals(parent.getType())) &&
                   parent.getParent() != null && "export_statement".equals(parent.getParent().getType())) { // Export of const/let/var: export const x = ...
            ancestor = parent.getParent(); // ancestor is now export_statement
            keywords.add("export");
            for (int i = 0; i < ancestor.getChildCount(); i++) {
                 TSNode child = ancestor.getChild(i);
                 if ("default".equals(child.getType())) { // Check for "default" keyword node type
                    keywords.add("default");
                    break;
                }
            }
        }


        // Add modifiers from the definition node itself (e.g., `public static readonly` on a class field, or `abstract` on class)
        // Tree-sitter stores modifiers typically as children of the definition node, not in a dedicated "modifiers" field node for TS.
        // Exception: public_field_definition might have them under a "modifiers" field in some grammars, but TS grammar puts them as direct children.
        // So we iterate direct children of definitionNode that are known modifier types.
        for (int i = 0; i < definitionNode.getChildCount(); i++) {
            TSNode modChild = definitionNode.getChild(i);
            if (modChild == null || modChild.isNull()) continue;

            String modChildType = modChild.getType();
            String modText = "";

            // Check against known modifier node types from LanguageSyntaxProfile
            if (getLanguageSyntaxProfile().modifierNodeTypes().contains(modChildType)) {
                 modText = textSlice(modChild, src).strip();
            } else { // Some modifiers might be simple keywords like "async", "static" that are not complex nodes
                 switch(modChildType) {
                     case "abstract": modText = "abstract"; break;
                     case "static": modText = "static"; break;
                     case "readonly": modText = "readonly"; break;
                     case "public": modText = "public"; break;
                     case "private": modText = "private"; break;
                     case "protected": modText = "protected"; break;
                     // "export" and "default" are handled by checking parent export_statement.
                     // "async" is handled separately in function rendering.
                 }
            }
            
            if (!modText.isEmpty()) {
                 if (modText.equals("export") && keywords.contains("export")) continue;
                 if (modText.equals("default") && keywords.contains("default")) continue;
                 keywords.add(modText);
            }
        }
        
        // Add `const/let/var` if definitionNode is `variable_declarator`
        // (and it wasn't an arrow function that got skipped for field.definition)
        if ("variable_declarator".equals(definitionNode.getType())) {
            TSNode lexicalOrVarDecl = parent; // parent of variable_declarator
            if (lexicalOrVarDecl != null && !lexicalOrVarDecl.isNull()) {
                String declType = lexicalOrVarDecl.getType();
                if ("lexical_declaration".equals(declType) || "variable_declaration".equals(declType)) {
                    TSNode kindNode = lexicalOrVarDecl.getChild(0); // const, let, var
                    if (kindNode != null && !kindNode.isNull()) {
                        // Make sure 'kindNode' is indeed one of 'const', 'let', 'var'
                        String kindText = textSlice(kindNode, src).strip();
                        if (kindText.equals("const") || kindText.equals("let") || kindText.equals("var")) {
                            keywords.add(kindText);
                        }
                    }
                }
            }
        }
        
        // Order the collected keywords:
        List<String> orderedKeywords = new ArrayList<>();
        // Standard order: export, default, abstract, static, visibility, readonly, kind
        if (keywords.contains("export")) orderedKeywords.add("export");
        if (keywords.contains("default")) orderedKeywords.add("default"); // Should come after export
        if (keywords.contains("abstract")) orderedKeywords.add("abstract");
        if (keywords.contains("static")) orderedKeywords.add("static");
        
        // Visibility: only one of public, protected, private
        if (keywords.contains("public")) orderedKeywords.add("public");
        else if (keywords.contains("protected")) orderedKeywords.add("protected");
        else if (keywords.contains("private")) orderedKeywords.add("private");
        
        if (keywords.contains("readonly")) orderedKeywords.add("readonly");

        // Kind: const, let, var. Only one should be present and usually first for var decls after export.
        // For TS, 'export const' is common. If 'export' is present, 'const' comes after.
        List<String> kindKeywords = new ArrayList<>();
        if (keywords.contains("const")) kindKeywords.add("const");
        if (keywords.contains("let")) kindKeywords.add("let");
        if (keywords.contains("var")) kindKeywords.add("var");

        if (!kindKeywords.isEmpty()) {
            // If 'export' or 'default' is already there, kind comes after.
            // Otherwise, kind might come first (e.g. "const x = 10;")
            // This needs to be robust. Let's insert kind keywords after export/default if present,
            // or at the beginning if no export/default.
            int insertIdx = 0;
            if (orderedKeywords.contains("export") || orderedKeywords.contains("default")){
                // Find index after 'export' or 'default'
                if (orderedKeywords.contains("default")) insertIdx = orderedKeywords.indexOf("default") + 1;
                else if (orderedKeywords.contains("export")) insertIdx = orderedKeywords.indexOf("export") + 1;
            }
            orderedKeywords.addAll(insertIdx, kindKeywords);
        }
        
        // Remove duplicates that might have crept in, though LinkedHashSet should prevent it for single words.
        // This is more for ensuring the specific order we want.
        return orderedKeywords.stream().distinct().toList();
    }

    @Override
    protected String getVisibilityPrefix(TSNode node, String src) {
        List<String> modifiers = getModifierKeywords(node, src);
        if (modifiers.isEmpty()) {
            return "";
        }
        // Join with space, and add a trailing space if not empty
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
