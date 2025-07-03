; Query optimized for testHelloTsSkeletons - simplified to avoid duplication

; Export class Greeter - capture the export_statement as the definition
(export_statement
  "export" @keyword.modifier
  declaration: (class_declaration name: (type_identifier) @class.name)) @class.definition

; Export function globalFunc - capture the export_statement as the definition
(export_statement
  "export" @keyword.modifier
  declaration: (function_declaration
    name: (identifier) @function.name
    parameters: (formal_parameters) @function.parameters
    return_type: (_)? @function.return_type
    body: (_)? @function.body)) @function.definition

; Export const PI - capture the export_statement as the definition
(export_statement
  "export" @keyword.modifier
  declaration: (lexical_declaration
    "const" @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name
      value: (_)? @field.value))) @field.definition

; Export interface Point - capture the export_statement as the definition
(export_statement
  "export" @keyword.modifier
  declaration: (interface_declaration name: (type_identifier) @class.name)) @class.definition

; Method definitions (for class members)
(method_definition
  name: (property_identifier) @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (_)? @function.body) @function.definition

; Class fields (only public_field_definition to avoid interface conflicts)
(public_field_definition
  name: (property_identifier) @field.name
  type: (_)? @field.type
  value: (_)? @field.value) @field.definition

; Interface properties
(property_signature
  name: (property_identifier) @field.name
  type: (_)? @field.type) @field.definition

; Interface method signatures
(method_signature
  name: (property_identifier) @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type) @function.definition

; Export enum Color - capture the export_statement as the definition
(export_statement
  "export" @keyword.modifier
  declaration: (enum_declaration name: (identifier) @class.name)) @class.definition

; Enum members for proper enum reconstruction - capture individual members
(enum_body
  ((property_identifier) @field.name) @field.definition)

; Export type StringOrNumber - capture the export_statement as the definition
(export_statement
  "export" @keyword.modifier
  declaration: (type_alias_declaration
    name: (type_identifier) @field.name
    value: (_) @field.value)) @field.definition

; Non-export type LocalDetails - match type_alias_declaration that's NOT a child of export_statement
; We'll match the parent program/statement_block that contains standalone type_alias_declaration
(program
  (type_alias_declaration
    name: (type_identifier) @field.name
    value: (_) @field.value) @field.definition)

; Also match within blocks/scopes that are not export statements
(statement_block
  (type_alias_declaration
    name: (type_identifier) @field.name
    value: (_) @field.value) @field.definition)

; Capture auxiliary nodes
(formal_parameters) @parameters
(type_annotation) @return_type_node