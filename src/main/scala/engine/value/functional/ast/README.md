


source expression
    ↓
parser
    ↓
parse tree / CST
    ↓
convert to your AST nodes
    ↓
store AST nodes in program memory
    ↓
execute recursively over the tree


uses variable references and parses C like programs into their specific syntax tree.

value += other * 2.0

could parse to:

assignment_expression
├── left
│   └── identifier "value"
├── operator "+="
└── right
    └── binary_expression
        ├── identifier "other"
        ├── "*"
        └── number "2.0"