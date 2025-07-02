; Classes, Interfaces, Enums, Modules (Namespaces)
; Export statements wrapping class-like declarations
(export_statement
  "export" @keyword.modifier
  (class_declaration name: (type_identifier) @class.name) @class.definition)

(export_statement
  "export" @keyword.modifier
  (abstract_class_declaration 
    "abstract" @keyword.modifier
    name: (type_identifier) @class.name) @class.definition)

(export_statement
  "export" @keyword.modifier
  (interface_declaration name: (type_identifier) @interface.name) @interface.definition)

(export_statement
  "export" @keyword.modifier
  (enum_declaration name: (identifier) @enum.name) @enum.definition)

(export_statement
  "export" @keyword.modifier
  (module name: [(identifier) (string) (nested_identifier)] @module.name) @module.definition)

; Regular class-like declarations without export
((class_declaration name: (type_identifier) @class.name) @class.definition
  (#not-parent-type? @class.definition "export_statement"))

((abstract_class_declaration 
  "abstract" @keyword.modifier
  name: (type_identifier) @class.name) @class.definition
  (#not-parent-type? @class.definition "export_statement"))

((interface_declaration name: (type_identifier) @interface.name) @interface.definition
  (#not-parent-type? @interface.definition "export_statement"))

((enum_declaration name: (identifier) @enum.name) @enum.definition
  (#not-parent-type? @enum.definition "export_statement"))

((module name: [(identifier) (string) (nested_identifier)] @module.name) @module.definition
  (#not-parent-type? @module.definition "export_statement")) ; module X {} or namespace X {}

; Functions and Methods (excluding arrow functions assigned to vars, handled below)
; Export statements wrapping function declarations
(export_statement
  "export" @keyword.modifier
  (function_declaration
    name: (identifier) @function.name
    parameters: (formal_parameters) @function.parameters
    return_type: (_)? @function.return_type
    body: (_)? @function.body) @function.definition)

; Export default function declarations
(export_statement
  "export" @keyword.modifier
  "default" @keyword.modifier
  (function_declaration
    name: (identifier) @function.name
    parameters: (formal_parameters) @function.parameters
    return_type: (_)? @function.return_type
    body: (_)? @function.body) @function.definition)

; Export default class declarations
(export_statement
  "export" @keyword.modifier
  "default" @keyword.modifier
  (class_declaration name: (type_identifier) @class.name) @class.definition)

; Export default interface declarations
(export_statement
  "export" @keyword.modifier
  "default" @keyword.modifier
  (interface_declaration name: (type_identifier) @interface.name) @interface.definition)

; Regular function declarations without export
((function_declaration
  name: (identifier) @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (_)? @function.body) @function.definition
  (#not-parent-type? @function.definition "export_statement"))

((method_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name ; also covers get/set accessors and computed names
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (_)? @function.body) @function.definition)

; TODO: Arrow function assignments - currently disabled to avoid conflicts with field patterns
; The test expects arrow function assignments to be treated as fields, not functions
; e.g., "const anArrowFunc = (msg: string): void => { ... }" should be a field signature

; Standalone arrow functions (e.g., in callbacks, IIFEs, not directly assigned to a var captured above)
((arrow_function
  parameters: (_)? @function.parameters
  return_type: (_)? @function.return_type
  body: (_)) @function.definition
  (#set! "default_name" "anonymous_arrow_function"))

; Function signature (e.g. in interfaces, type literals, function overloads)
((function_signature
  name: (identifier) @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type) @function.definition)

((method_signature ; for interfaces/type literals
  name: [(property_identifier) (string) (number) (computed_property_name)] @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type) @function.definition)

; Generator functions
((generator_function_declaration
  name: (identifier) @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (statement_block) @function.body) @function.definition)

; Fields (Variables, Class properties, Interface properties, Enum members)

; Top-level/local variables (const, let, var) - EXCLUDING those whose value is an arrow function
; Export statement wrapping lexical declaration
(export_statement
  "export" @keyword.modifier
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name
      value: (_)? @field.value) @field.definition))
  (#not-parent-type? @field.definition "for_in_statement")
  (#not-parent-type? @field.definition "for_of_statement")

; Export statement wrapping variable declaration (var)
(export_statement
  "export" @keyword.modifier
  (variable_declaration
    "var" @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name
      value: (_)? @field.value) @field.definition))

; Non-export variable declarations - temporarily simplified to avoid conflicts
; Focus on getting export patterns working correctly first
; TODO: Add back comprehensive non-export patterns with working exclusion mechanism

; Basic top-level variable declarations that are clearly not in export context
(program
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name
      value: (_)? @field.value) @field.definition))

(program  
  (variable_declaration
    "var" @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name
      value: (_)? @field.value) @field.definition))

; Class fields (public_field_definition also covers private, protected, static, readonly)
(public_field_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @field.name
  type: (_)? @field.type
  value: (_)? @field.value) @field.definition

; Interface/type literal properties
(property_signature
  name: [(property_identifier) (string) (number) (computed_property_name)] @field.name
  type: (_)? @field.type) @field.definition

; Enum members
(enum_declaration
  body: (enum_body
    ((property_identifier) @field.name @field.definition))) ; Member without value

(enum_declaration
  body: (enum_body
    (enum_assignment
      name: (property_identifier) @field.name
      value: (_)) @field.definition)) ; Member with value


; Decorators
(decorator (identifier) @decorator.name) @decorator.definition ; @foo
(decorator (call_expression function: (identifier) @decorator.name) @decorator.definition) ; @foo()
(decorator (call_expression function: (member_expression property: (property_identifier) @decorator.name)) @decorator.definition) ; @obj.foo()

; Type Aliases
(
  (type_alias_declaration
    name: (type_identifier) @typealias.name
    type_parameters: (_)? @typealias.type_parameters ; Optional: Capture type parameters for alias
    value: (_) @typealias.value       ; Capture the actual type being aliased
  ) @typealias.definition
)
(
  (export_statement
    "export"
    "default"
    declaration: (type_alias_declaration
      name: (type_identifier) @typealias.name
      type_parameters: (_)? @typealias.type_parameters
      value: (_) @typealias.value
    )
  ) @typealias.definition
)


; Captures for modifiers, parameters, return types (used by TreeSitterAnalyzer logic, not direct CUs)
(formal_parameters) @parameters
(type_annotation) @return_type_node  ; General type annotation
(predefined_type) @predefined_type_node ; e.g. string, number
(type_identifier) @type_identifier_node ; e.g. MyClass, InterfaceName

; Note: keyword.modifier captures are now scoped within specific definition patterns above
; This ensures they are properly associated with their corresponding definition nodes
