; ============================================================================
; REUSABLE NAME PATTERNS (for reference and consistency)
; ============================================================================
; member_name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)]
; interface_member_name: [(property_identifier) (string) (number) (computed_property_name)]

; Namespace/Module declarations - can match at any level
(internal_module 
  name: (_) @class.name) @class.definition

; ============================================================================
; EXPORT STATEMENTS  
; ============================================================================

; ============================================================================
; EXPORT STATEMENTS (consolidated using alternation)
; ============================================================================

; Export statements (both default and regular) for class-like declarations
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  [
    (class_declaration name: (type_identifier) @class.name)
    (abstract_class_declaration name: (type_identifier) @class.name)
    (enum_declaration name: (identifier) @class.name)
    (interface_declaration name: (type_identifier) @class.name)
  ]) @class.definition

; Export statements with decorators for class declarations
(
  (decorator)*
  . (export_statement
      "export" @keyword.modifier
      declaration: (class_declaration name: (type_identifier) @class.name)) @class.definition
)

; Export statements (both default and regular) for functions
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (function_declaration
    name: (identifier) @function.name)) @function.definition

; Export statements (both default and regular) for type aliases
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (type_alias_declaration
    name: (type_identifier) @field.name) @field.definition)


; ============================================================================
; TYPE ALIAS DECLARATIONS (non-export)
; ============================================================================

; Non-export type alias declarations
(program 
  (type_alias_declaration
    name: (type_identifier) @field.name) @field.definition)

; ============================================================================
; LEXICAL DECLARATIONS (const, let) - consolidated patterns
; ============================================================================

; Arrow functions in const/let (export and non-export)
(export_statement
  "export" @keyword.modifier
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @function.name
      value: (arrow_function)))) @function.definition

(program
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @function.name
      value: (arrow_function))) @function.definition)

; Other values in const/let (export and non-export)
(export_statement
  "export" @keyword.modifier
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name) @field.definition))

(program
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name)) @field.definition)

; ============================================================================
; VARIABLE DECLARATIONS (var) - consolidated
; ============================================================================

; Export var declarations
(export_statement
  "export" @keyword.modifier
  (variable_declaration
    "var" @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name) @field.definition))

; Top-level non-export var declarations
(program
  (variable_declaration
    "var" @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name) @field.definition))

; ============================================================================
; FUNCTION DECLARATIONS
; ============================================================================

; Top-level non-export function declarations
(program
  (function_declaration
    name: (identifier) @function.name) @function.definition)

; ============================================================================
; FUNCTION SIGNATURES (Overloads)
; ============================================================================

; Export function signatures (overloads)
(export_statement
  "export" @keyword.modifier
  (function_signature
    name: (identifier) @function.name)) @function.definition

; Top-level non-export function signatures
(program
  (function_signature
    name: (identifier) @function.name) @function.definition)

; ============================================================================
; CLASS/INTERFACE MEMBERS
; ============================================================================

; Method definitions (with optional decorators) - uses member_name pattern
(
  (decorator)*
  . (method_definition
      name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name) @function.definition
)

; Interface method signatures - uses interface_member_name pattern
(method_signature
  name: [(property_identifier) (string) (number) (computed_property_name)] @function.name) @function.definition

; Abstract method signatures (captured anywhere they appear)
; Note: Abstract methods are typically method_signature nodes in abstract classes

; Class fields (with optional decorators) - uses member_name pattern
(
  (decorator)*
  . (public_field_definition
      name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @field.name) @field.definition
)

; Interface properties - uses interface_member_name pattern
(property_signature
  name: [(property_identifier) (string) (number) (computed_property_name)] @field.name) @field.definition

; ============================================================================
; ENUM MEMBERS
; ============================================================================

; Enum members for proper enum reconstruction - capture individual members
(enum_body
  ((property_identifier) @field.name) @field.definition)

