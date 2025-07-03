; Query optimized for testDefaultExportSkeletons
; Focuses on export statements with special handling for default exports

; Export default class - highest priority
(export_statement
  "export" @keyword.modifier
  "default" @keyword.modifier
  (class_declaration name: (type_identifier) @class.name) @class.definition)

; Export default function
(export_statement
  "export" @keyword.modifier
  "default" @keyword.modifier
  (function_declaration
    name: (identifier) @function.name
    parameters: (formal_parameters) @function.parameters
    return_type: (_)? @function.return_type
    body: (_)? @function.body) @function.definition)

; Export default type alias
(export_statement
  "export" @keyword.modifier
  "default" @keyword.modifier
  (type_alias_declaration
    name: (type_identifier) @field.name
    value: (_) @field.value) @field.definition)

; Regular export const/let statements (for utilityRate)
(export_statement
  "export" @keyword.modifier
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name
      value: (_)? @field.value) @field.definition))

; Regular export class (for AnotherNamedClass)
; This will match both regular and default exports, but the TypescriptAnalyzer
; deduplication logic will handle the conflict by preferring the default export
(export_statement
  "export" @keyword.modifier
  (class_declaration name: (type_identifier) @class.name) @class.definition)

; Method definitions (for class members)
(method_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (_)? @function.body) @function.definition

; Class fields
(public_field_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @field.name
  type: (_)? @field.type
  value: (_)? @field.value) @field.definition

; Type aliases (non-export, for LocalDetails)
(type_alias_declaration
  name: (type_identifier) @field.name
  type_parameters: (_)? @field.type
  value: (_) @field.value) @field.definition

; Capture auxiliary nodes
(formal_parameters) @parameters
(type_annotation) @return_type_node
(predefined_type) @predefined_type_node
(type_identifier) @type_identifier_node