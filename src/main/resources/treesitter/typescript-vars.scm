; Query optimized for testVarsTsSkeletons  

; export const MAX_USERS = 100;
(export_statement
  "export" @keyword.modifier
  (lexical_declaration
    "const" @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name
      value: (_)? @field.value) @field.definition))

; export var legacyVar = "legacy";  
(export_statement
  "export" @keyword.modifier
  (variable_declaration
    "var" @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name
      value: (_)? @field.value) @field.definition))

; let currentUser: string = "Alice"; (non-export)
(lexical_declaration
  "let" @keyword.modifier
  (variable_declarator
    name: (identifier) @field.name
    value: (_)? @field.value) @field.definition)

; const anArrowFunc = (msg: string): void => { ... }; (arrow function)
(lexical_declaration
  "const" @keyword.modifier
  (variable_declarator
    name: (identifier) @function.name
    value: (arrow_function
      parameters: (_)? @function.parameters
      return_type: (_)? @function.return_type
      body: (_)) @function.definition))

; const config = { ... }; (object literal)
(lexical_declaration
  "const" @keyword.modifier
  (variable_declarator
    name: (identifier) @field.name
    value: (object) @field.value) @field.definition)

; function localHelper(): string { return "helper"; }
(function_declaration
  name: (identifier) @function.name
  parameters: (formal_parameters) @function.parameters
  return_type: (_)? @function.return_type
  body: (_)? @function.body) @function.definition

; Capture auxiliary nodes
(formal_parameters) @parameters
(type_annotation) @return_type_node
(predefined_type) @predefined_type_node
(type_identifier) @type_identifier_node