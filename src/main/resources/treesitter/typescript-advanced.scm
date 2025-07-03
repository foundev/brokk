; Query optimized for testAdvancedTsSkeletonsAndFeatures
; Focuses on: decorators, generic classes, advanced features

; Export statements with classes
(export_statement
  "export" @keyword.modifier
  (class_declaration name: (type_identifier) @class.name) @class.definition)

; Class declarations (decorated or not)
(class_declaration name: (type_identifier) @class.name) @class.definition

; Decorators - captured separately
(decorator) @decorator.definition

; Abstract class declarations
(abstract_class_declaration 
  "abstract" @keyword.modifier
  name: (type_identifier) @class.name) @class.definition

; Decorated method definitions
(
  (decorator)+ @decorator.definition
  . (method_definition
      name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name
      parameters: (formal_parameters) @function.parameters
      return_type: (_)? @function.return_type
      body: (_)? @function.body) @function.definition
)

; Regular method definitions with all modifiers
(method_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (_)? @function.body) @function.definition

; Decorated class fields
(
  (decorator)+ @decorator.definition
  . (public_field_definition
      name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @field.name
      type: (_)? @field.type
      value: (_)? @field.value) @field.definition
)

; Regular class fields with modifiers
(public_field_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @field.name
  type: (_)? @field.type
  value: (_)? @field.value) @field.definition

; Note: Decorators are now captured inline with their target declarations above

; Type aliases
(type_alias_declaration
  name: (type_identifier) @typealias.name
  type_parameters: (_)? @typealias.type_parameters
  value: (_) @typealias.value) @typealias.definition

; Variable declarations
(lexical_declaration
  ["const" "let"] @keyword.modifier
  (variable_declarator
    name: (identifier) @field.name
    value: (_)? @field.value) @field.definition)

; Interfaces
(interface_declaration name: (type_identifier) @interface.name) @interface.definition

; Interface properties
(property_signature
  name: [(property_identifier) (string) (number) (computed_property_name)] @field.name
  type: (_)? @field.type) @field.definition

; Interface method signatures
(method_signature
  name: [(property_identifier) (string) (number) (computed_property_name)] @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type) @function.definition

; Capture auxiliary nodes
(formal_parameters) @parameters
(type_annotation) @return_type_node
(predefined_type) @predefined_type_node
(type_identifier) @type_identifier_node