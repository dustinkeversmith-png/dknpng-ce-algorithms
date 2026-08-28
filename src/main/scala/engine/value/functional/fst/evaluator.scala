// We will have our operator nodes, assignments, all that stuff up here

import java.nio.ByteBuffer

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

    def evaluate_program(program: ProgramNode): Unit =
        var statementIndex = 0
        while statementIndex < program.statements.length && !this.has_returned do
            this.evaluate_node(program.statements(statementIndex))
            statementIndex += 1

    def evaluate_block(block: BlockNode): Unit =
        var statementIndex = 0
        while statementIndex < block.statements.length && !this.has_returned do
            this.evaluate_node(block.statements(statementIndex))
            statementIndex += 1

    def evaluate_node(node: SemanticNode): Any =
        node match
            case ProgramNode(statements) =>
                this.evaluate_program(ProgramNode(statements))
            case MemberAccessNode(_, _) =>
                // Here the last hierarchically visited variable node can be known to reference the . or member access
                // MemberName = node.memberName
                // MemberAccess = args[VariableName][MemberName], from here we have the internal value where we can then set values using the Value Class as it is normally done.
            case IndexAccessNode(_, _) =>

                 // Fill in the gaps here
            case VariableNode(_)  =>


                val (value, field) = this.resolve(node)
                this.read(value, field)
            case NumericLiteralNode(value) =>
                value
            case StringLiteralNode(value) =>
                value
            case UnaryOperatorNode(operator, value) =>
                val evaluatedValue = this.evaluate_node(value)
                operator match
                    case "+" => this.number(evaluatedValue)
                    case "-" => -this.number(evaluatedValue)
                    case "!" => !this.boolean(evaluatedValue)
                    case _ => throw new IllegalArgumentException(s"Unknown unary operator: $operator")
            case BinaryOperatorNode(left, "&&", right) =>
                this.boolean(this.evaluate_node(left)) && this.boolean(this.evaluate_node(right))
            case BinaryOperatorNode(left, "||", right) =>
                this.boolean(this.evaluate_node(left)) || this.boolean(this.evaluate_node(right))
            case BinaryOperatorNode(left, operator, right) =>
                val leftValue = this.evaluate_node(left)
                val rightValue = this.evaluate_node(right)
                operator match
                    case "+" => this.number(leftValue) + this.number(rightValue)
                    case "-" => this.number(leftValue) - this.number(rightValue)
                    case "*" => this.number(leftValue) * this.number(rightValue)
                    case "/" => this.number(leftValue) / this.number(rightValue)
                    case "%" => this.number(leftValue) % this.number(rightValue)
                    case "<" => this.number(leftValue) < this.number(rightValue)
                    case "<=" => this.number(leftValue) <= this.number(rightValue)
                    case ">" => this.number(leftValue) > this.number(rightValue)
                    case ">=" => this.number(leftValue) >= this.number(rightValue)
                    case "==" => leftValue == rightValue
                    case "!=" => leftValue != rightValue
                    case _ => throw new IllegalArgumentException(s"Unknown binary operator: $operator")
            case TernaryOperatorNode(condition, whenTrue, whenFalse) =>
                if this.boolean(this.evaluate_node(condition)) then this.evaluate_node(whenTrue)
                else this.evaluate_node(whenFalse)
            case AssignmentOperatorNode(target, operator, assignedValue) =>
                val (value, field) = this.resolve(target)
                val rightValue = this.evaluate_node(assignedValue)
                val finalValue = operator match
                    case "=" => rightValue
                    case "+=" => this.number(this.read(value, field)) + this.number(rightValue)
                    case "-=" => this.number(this.read(value, field)) - this.number(rightValue)
                    case "*=" => this.number(this.read(value, field)) * this.number(rightValue)
                    case "/=" => this.number(this.read(value, field)) / this.number(rightValue)
                    case "%=" => this.number(this.read(value, field)) % this.number(rightValue)
                    case _ => throw new IllegalArgumentException(s"Unknown assignment operator: $operator")
                this.write(value, field, finalValue)
                finalValue
            case DeclarationNode(valueType, name, initialValue) =>
                val evaluatedValue = initialValue.map(this.evaluate_node).getOrElse(0.0)
                val baseType =
                    if TypeRegistry.contains(valueType) then valueType
                    else evaluatedValue match
                        case _: Boolean => "byte"
                        case _ => "double"
                val stackValue = new Value(name, Vector.empty, Map("value" -> baseType))
                stackValue.index_fields()
                stackValue.allocate()
                this.write(stackValue, "value", evaluatedValue)
                this.tree.stack(name) = stackValue
                evaluatedValue
            case BlockNode(statements) =>
                this.evaluate_block(BlockNode(statements))
            case IfNode(condition, thenBranch, elseBranch) =>
                if this.boolean(this.evaluate_node(condition)) then this.evaluate_block(thenBranch)
                else elseBranch.foreach(this.evaluate_block)
            case WhileNode(condition, body) =>
                while !this.has_returned && this.boolean(this.evaluate_node(condition)) do
                    this.evaluate_block(body)
            case ReturnNode(value) =>
                value match
                    case Some(returnValue) =>
                        returnValue match
                            case VariableNode(_) | MemberAccessNode(_, _) | IndexAccessNode(_, _) =>
                                val (storedValue, _) = this.resolve(returnValue)
                                this.returned = Some(storedValue)
                            case _ =>
                                val evaluatedValue = this.evaluate_node(returnValue)
                                val result = new Value("return", Vector.empty, Map("value" -> "double"))
                                result.index_fields()
                                result.allocate()
                                this.write(result, "value", evaluatedValue)
                                this.returned = Some(result)
                    case None =>
                        this.returned = None
                this.has_returned = true
            case FunctionCallNode(_, _) =>
                throw new UnsupportedOperationException("Function call evaluation requires a registered Functional")

    

