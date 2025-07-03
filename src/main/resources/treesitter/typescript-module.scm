; Query optimized for testModuleTsSkeletons
; Focuses on: namespace/module declarations and their contents

; Try multiple possible node types for namespace/module declarations
(module 
  name: [(identifier) (string) (nested_identifier)] @class.name) @class.definition

; Try internal_module (this is what TypeScript namespaces actually parse as)
(internal_module 
  name: [(identifier) (string) (nested_identifier)] @class.name) @class.definition

; Try ambient_declaration with module
(ambient_declaration
  (module 
    name: [(identifier) (string) (nested_identifier)] @class.name) @class.definition)

; Export statements wrapping modules
(export_statement
  "export" @keyword.modifier
  (module 
    name: [(identifier) (string) (nested_identifier)] @class.name) @class.definition)

; Export statements wrapping class declarations  
(export_statement
  "export" @keyword.modifier
  declaration: (class_declaration name: (type_identifier) @class.name) @class.definition)

; Export statements wrapping function declarations
(export_statement
  "export" @keyword.modifier
  declaration: (function_declaration
    name: (identifier) @function.name
    parameters: (formal_parameters) @function.parameters
    return_type: (_)? @function.return_type
    body: (_)? @function.body) @function.definition)

; Export statements wrapping lexical declarations - arrow functions
(export_statement
  "export" @keyword.modifier
  declaration: (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @function.name
      value: (arrow_function
        parameters: (formal_parameters) @function.parameters
        return_type: (_)? @function.return_type
        body: (_)? @function.body) @function.definition)))

; Export statements wrapping lexical declarations - other values
(export_statement
  "export" @keyword.modifier
  declaration: (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name
      value: (_)? @field.value) @field.definition))

(variable_declaration
  "var" @keyword.modifier
  (variable_declarator
    name: (identifier) @field.name
    value: (_)? @field.value) @field.definition)

; Method definitions
(method_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (_)? @function.body) @function.definition

; Interface method signatures
(method_signature
  name: [(property_identifier) (string) (number) (computed_property_name)] @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type) @function.definition

; Class fields
(public_field_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @field.name
  type: (_)? @field.type
  value: (_)? @field.value) @field.definition

; Interface properties
(property_signature
  name: [(property_identifier) (string) (number) (computed_property_name)] @field.name
  type: (_)? @field.type) @field.definition

; Export statements wrapping enum declarations
(export_statement
  "export" @keyword.modifier
  declaration: (enum_declaration name: (identifier) @class.name) @class.definition)

; Export statements wrapping interface declarations
(export_statement
  "export" @keyword.modifier
  declaration: (interface_declaration name: (type_identifier) @class.name) @class.definition)

; Export statements wrapping type alias declarations
(export_statement
  "export" @keyword.modifier
  declaration: (type_alias_declaration name: (type_identifier) @field.name) @field.definition)

; Enum members for proper enum reconstruction - capture individual members
(enum_body
  ((property_identifier) @field.name) @field.definition)

; Capture auxiliary nodes
(formal_parameters) @parameters
(type_annotation) @return_type_node
(predefined_type) @predefined_type_node
(type_identifier) @type_identifier_node