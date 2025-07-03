; Classes, Interfaces, Enums, Modules (Namespaces)
((class_declaration name: (type_identifier) @class.name) @class.definition)
((abstract_class_declaration name: (type_identifier) @class.name) @class.definition)
((interface_declaration name: (type_identifier) @interface.name) @interface.definition)
((enum_declaration name: (identifier) @enum.name) @enum.definition)
((module name: [(identifier) (string) (nested_identifier)] @module.name) @module.definition) ; module X {} or namespace X {}

; Functions and Methods (excluding arrow functions assigned to vars, handled below)
((function_declaration
  name: (identifier) @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (_)? @function.body) @function.definition)

((method_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name ; also covers get/set accessors and computed names
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (_)? @function.body) @function.definition)

; Arrow function assigned to a variable (const, let, var)
; This rule is more specific and should be prioritized by the analyzer for these cases.
(lexical_declaration
  (variable_declarator
    name: (identifier) @function.name ; Name of the const/let
    value: (arrow_function
      parameters: (_)? @function.parameters
      return_type: (_)? @function.return_type
      body: (_)) @function.definition)) ; @function.definition is the arrow_function node

(variable_declaration ; for 'var' keyword
  (variable_declarator
    name: (identifier) @function.name ; Name of the var
    value: (arrow_function
      parameters: (_)? @function.parameters
      return_type: (_)? @function.return_type
      body: (_)) @function.definition)) ; @function.definition is the arrow_function node

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
; (variable_declarator node itself is the definition)
(variable_declarator
  name: (identifier) @field.name
  value: (_)? @field.value) @field.definition
  (#not-parent-type? @field.definition "for_in_statement")
  (#not-parent-type? @field.definition "for_of_statement")
  ; Further condition to ensure value is not an arrow_function will be handled in Java code
  ; to avoid making this query too complex or slow, as @field.value can be diverse.

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
