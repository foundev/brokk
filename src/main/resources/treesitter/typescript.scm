; Classes, Interfaces, Enums, Modules (Namespaces)
((class_declaration name: (identifier) @class.name) @class.definition)
((abstract_class_declaration name: (identifier) @class.name) @class.definition)
((interface_declaration name: (identifier) @class.name) @interface.definition)
((enum_declaration name: (identifier) @class.name) @enum.definition)
((module name: (_) @class.name .) @module.definition (#not-match? @class.name "^\"")) ; module X {} or namespace X {}

; Functions and Methods
((function_declaration
  name: (identifier) @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (_)? @function.body) @function.definition)

((method_definition
  name: [(property_identifier) (private_property_identifier) (string_literal) (number_literal)] @function.name ; also covers get/set accessors and computed names
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (_)? @function.body) @function.definition)

((arrow_function
  parameters: (_)? @function.parameters ; can be identifier or formal_parameters
  return_type: (_)? @function.return_type
  body: (_)) @function.definition
  (#set! "default_name" "anonymous_arrow_function"))

; Function signature (e.g. in interfaces, type literals)
((function_signature
  name: (property_identifier) @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type) @function.definition)
((method_signature ; for interfaces/type literals
  name: (property_identifier) @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type) @function.definition)


; Generator functions
((generator_function_declaration
  name: (identifier) @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (statement_block) @function.body) @function.definition)

; Fields (Variables, Class properties, Interface properties, Enum members)

; Top-level/local variables (const, let, var)
(variable_declarator
  name: (identifier) @field.name
  value: (_)? @field.value) @field.definition
  (#not-parent-type? @field.definition "for_in_statement")
  (#not-parent-type? @field.definition "for_of_statement")

; Class fields (public_field_definition also covers private, protected, static, readonly)
(public_field_definition
  name: [(property_identifier) (private_property_identifier) (string_literal) (number_literal)] @field.name
  type: (_)? @field.type
  value: (_)? @field.value) @field.definition

; Interface/type literal properties
(property_signature
  name: [(property_identifier) (string_literal) (number_literal)] @field.name
  type_annotation: (_)? @field.type) @field.definition

; Enum members
(enum_member name: (property_identifier) @field.name value: (_)? @field.value) @field.definition


; Decorators
(decorator (identifier) @decorator.name) @decorator.definition ; @foo
(decorator (call_expression function: (identifier) @decorator.name) @decorator.definition) ; @foo()
(decorator (call_expression function: (member_expression property: (property_identifier) @decorator.name)) @decorator.definition) ; @obj.foo()

; Captures for modifiers, parameters, return types (used by TreeSitterAnalyzer logic, not direct CUs)
(formal_parameters) @parameters
(type_annotation) @return_type_node  ; General type annotation
(predefined_type) @predefined_type_node ; e.g. string, number
(type_identifier) @type_identifier_node ; e.g. MyClass, InterfaceName

; Capture export keyword specifically if it's a child of 'modifiers'
((modifiers (export_keyword)) @export.keyword)
