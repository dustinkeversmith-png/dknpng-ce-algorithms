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

    def evaluate(): Option[Value] =
        this.returned = None
        this.has_returned = false
        this.evaluate_program(this.tree.program)
        this.returned

    def evaluate_program(program: ProgramNode): Value =
        var evaluatedValue = this.tree.registry.literal(0.0)
        var statementIndex = 0

        while statementIndex < program.statements.length && !this.has_returned do
            evaluatedValue = this.evaluate_node(program.statements(statementIndex))
            statementIndex += 1

        evaluatedValue

    def evaluate_block(block: BlockNode): Value =
        var evaluatedValue = this.tree.registry.literal(0.0)
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
                this.evaluate_node(value)(member)
            case IndexAccessNode(value, index) =>
                // Fill in the gaps here
                // Index access uses the current hierarchical Value view, not a flattened resolver.
                this.evaluate_node(value)(this.evaluate_node(index).integer())
            case NumericLiteralNode(value) =>
                this.tree.registry.literal(value)
            case StringLiteralNode(_) =>
                throw new UnsupportedOperationException("String Value registration is not available in the base type pack")
            case UnaryOperatorNode(operator, value) =>
                val evaluatedValue = this.evaluate_node(value)
                operator match
                    case "+" => evaluatedValue.operators("unary+")()
                    case "-" => evaluatedValue.operators("unary-")()
                    case "!" => evaluatedValue.operators("!")()
                    case _ => throw new IllegalArgumentException(s"Unknown unary operator: $operator")
            case BinaryOperatorNode(left, operator, right) =>
                val leftValue = this.evaluate_node(left)

                // These preserve short-circuit behavior while the actual operation remains a Value operator.
                if operator == "&&" && !leftValue.truth() then leftValue
                else if operator == "||" && leftValue.truth() then leftValue
                else leftValue.operators(operator)(this.evaluate_node(right))
            case TernaryOperatorNode(condition, whenTrue, whenFalse) =>
                if this.evaluate_node(condition).truth() then this.evaluate_node(whenTrue)
                else this.evaluate_node(whenFalse)
            case AssignmentOperatorNode(target, operator, assignedValue) =>
                val targetValue = this.evaluate_node(target)
                val rightValue = this.evaluate_node(assignedValue)
                targetValue.operators(operator)(rightValue)
            case DeclarationNode(valueType, name, initialValue) =>
                val stackValue = this.tree.registry.value(name, valueType)
                initialValue.foreach(value => stackValue.operators("=")(this.evaluate_node(value)))
                this.tree.stack(name) = stackValue
                stackValue
            case BlockNode(statements) =>
                this.evaluate_block(BlockNode(statements))
            case IfNode(condition, thenBranch, elseBranch) =>
                if this.evaluate_node(condition).truth() then this.evaluate_block(thenBranch)
                else
                    elseBranch match
                        case Some(branch) => this.evaluate_block(branch)
                        case None => this.tree.registry.literal(0.0)
            case WhileNode(condition, body) =>
                var evaluatedValue = this.tree.registry.literal(0.0)
                while !this.has_returned && this.evaluate_node(condition).truth() do
                    evaluatedValue = this.evaluate_block(body)
                evaluatedValue
            case ReturnNode(value) =>
                this.returned = value.map(this.evaluate_node)
                this.has_returned = true
                this.returned.getOrElse(this.tree.registry.literal(0.0))
            case FunctionCallNode(_, _) =>
                throw new UnsupportedOperationException("Function call evaluation requires a registered operator function")
