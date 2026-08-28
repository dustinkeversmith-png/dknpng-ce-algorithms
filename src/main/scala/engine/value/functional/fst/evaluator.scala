// We will have our operator nodes, assignments, all that stuff up here

trait Evalulator:

    def evaluate(): Option[Value]
        // Traverse the FunctionalAst
        // The actual FunctionalTree will have like an array of in order AstTrees parsed from the parser
        // So we iterate through those, do the evaluation/traversal of the nodes, mutating the arguments internal values, which may more may not trigger a recursive call for a different functional associated with something else but ignore that
        // Nodes will have like variable names in them, so we can then use the args to associate those of course.
        // Also the internal vars like temp vars can be created on the vars tree in the program and reused of course.


final class Evaluator(var tree: FunctionalSemanticTree) extends Evalulator:

    var returned: Option[Value] = None
    var has_returned: Boolean = false
    var baseTypes: BaseTypes = new BaseTypes()
    baseTypes.registry = this.tree.registry

    def evaluate(): Option[Value] =
        this.returned = None
        this.has_returned = false
        this.evaluate_program(this.tree.program)
        this.returned

    def evaluate_program(program: ProgramNode): Value =
        var evaluatedValue = new Value("program", Vector.empty, Map("value" -> "double"))
        evaluatedValue.registry = this.tree.registry
        evaluatedValue.index_fields()
        evaluatedValue.allocate()
        var statementIndex = 0

        while statementIndex < program.statements.length && !this.has_returned do
            evaluatedValue = this.evaluate_node(program.statements(statementIndex))
            statementIndex += 1

        evaluatedValue

    def evaluate_block(block: BlockNode): Value =
        var evaluatedValue = new Value("block", Vector.empty, Map("value" -> "double"))
        evaluatedValue.registry = this.tree.registry
        evaluatedValue.index_fields()
        evaluatedValue.allocate()
        var statementIndex = 0

        while statementIndex < block.statements.length && !this.has_returned do
            evaluatedValue = this.evaluate_node(block.statements(statementIndex))
            statementIndex += 1

        evaluatedValue

    def evaluate_node(node: SemanticNode): Value =
        node match
            case ProgramNode(statements) =>
                this.evaluate_program(ProgramNode(statements))
            case VariableNode(name) =>
                // Variable names have already been mapped to Value references by the FST.
                this.tree.stack.get(name)
                    .orElse(this.tree.args.get(name))
                    .getOrElse(throw new NoSuchElementException(s"Unknown value: $name"))
            case MemberAccessNode(value, member) =>
                // Here the last hierarchically visited variable node can be known to reference the . or member access
                // MemberName = node.memberName
                // MemberAccess = args[VariableName][MemberName], from here we have the internal value where we can then set values using the Value Class as it is normally done.
                // Member access keeps returning a Value view backed by the original argument memory.
                this.evaluate_node(value).reference_member(member)
            case IndexAccessNode(value, index) =>
                // Fill in the gaps here
                // Index access uses the current hierarchical Value view, not a flattened resolver.
                val elementIndex = this.baseTypes.read_value(this.evaluate_node(index)).toInt
                this.evaluate_node(value).reference_element(Array(elementIndex))
            case NumericLiteralNode(value) =>
                val literalValue = new Value("literal", Vector.empty, Map("value" -> "double"))
                literalValue.registry = this.tree.registry
                literalValue.index_fields()
                literalValue.allocate()
                this.baseTypes.write_value(literalValue, value)
            case StringLiteralNode(_) =>
                throw new UnsupportedOperationException("String Value registration is not available in the base type pack")
            case UnaryOperatorNode(operator, value) =>
                val evaluatedValue = this.evaluate_node(value)
                operator match
                    case "+" => evaluatedValue.operator("unary+")()
                    case "-" => evaluatedValue.operator("unary-")()
                    case "!" => evaluatedValue.operator("!")()
                    case _ => throw new IllegalArgumentException(s"Unknown unary operator: $operator")
            case BinaryOperatorNode(left, operator, right) =>
                val leftValue = this.evaluate_node(left)

                // These preserve short-circuit behavior while the actual operation remains a Value operator.
                if operator == "&&" && this.baseTypes.read_value(leftValue) == 0.0 then leftValue
                else if operator == "||" && this.baseTypes.read_value(leftValue) != 0.0 then leftValue
                else leftValue.operator(operator)(this.evaluate_node(right))
            case TernaryOperatorNode(condition, whenTrue, whenFalse) =>
                if this.baseTypes.read_value(this.evaluate_node(condition)) != 0.0 then this.evaluate_node(whenTrue)
                else this.evaluate_node(whenFalse)
            case AssignmentOperatorNode(target, operator, assignedValue) =>
                val targetValue = this.evaluate_node(target)
                val rightValue = this.evaluate_node(assignedValue)
                targetValue.operator(operator)(rightValue)
            case DeclarationNode(valueType, name, initialValue) =>
                val stackValue = new Value(name, Vector.empty, Map("value" -> valueType))
                stackValue.registry = this.tree.registry
                stackValue.index_fields()
                stackValue.allocate()
                initialValue.foreach(value => stackValue.operator("=")(this.evaluate_node(value)))
                this.tree.stack(name) = stackValue
                stackValue
            case BlockNode(statements) =>
                this.evaluate_block(BlockNode(statements))
            case IfNode(condition, thenBranch, elseBranch) =>
                if this.baseTypes.read_value(this.evaluate_node(condition)) != 0.0 then this.evaluate_block(thenBranch)
                else
                    elseBranch match
                        case Some(branch) => this.evaluate_block(branch)
                        case None =>
                            val emptyIfValue = new Value("if", Vector.empty, Map("value" -> "double"))
                            emptyIfValue.registry = this.tree.registry
                            emptyIfValue.index_fields()
                            emptyIfValue.allocate()
                            emptyIfValue
            case WhileNode(condition, body) =>
                var evaluatedValue = new Value("while", Vector.empty, Map("value" -> "double"))
                evaluatedValue.registry = this.tree.registry
                evaluatedValue.index_fields()
                evaluatedValue.allocate()
                while !this.has_returned && this.baseTypes.read_value(this.evaluate_node(condition)) != 0.0 do
                    evaluatedValue = this.evaluate_block(body)
                evaluatedValue
            case ReturnNode(value) =>
                this.returned = value.map(this.evaluate_node)
                this.has_returned = true
                this.returned.getOrElse {
                    val emptyReturnValue = new Value("return", Vector.empty, Map("value" -> "double"))
                    emptyReturnValue.registry = this.tree.registry
                    emptyReturnValue.index_fields()
                    emptyReturnValue.allocate()
                    emptyReturnValue
                }
            case FunctionCallNode(_, _) =>
                throw new UnsupportedOperationException("Function call evaluation requires a registered operator function")
