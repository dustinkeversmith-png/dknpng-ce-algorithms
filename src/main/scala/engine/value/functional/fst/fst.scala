
package value

import scala.collection.mutable.HashMap


sealed trait SemanticNode

final case class ProgramNode(statements: Vector[SemanticNode]) extends SemanticNode
final case class VariableNode(name: String) extends SemanticNode
final case class NumericLiteralNode(value: Double) extends SemanticNode
final case class StringLiteralNode(value: String) extends SemanticNode
final case class MemberAccessNode(value: SemanticNode, member: String) extends SemanticNode
final case class IndexAccessNode(value: SemanticNode, index: SemanticNode) extends SemanticNode
final case class MultiIndexAccessNode(value: SemanticNode, indices: Vector[SemanticNode]) extends SemanticNode
final case class FunctionCallNode(function: SemanticNode, arguments: Vector[SemanticNode]) extends SemanticNode
final case class UnaryOperatorNode(operator: String, value: SemanticNode) extends SemanticNode
final case class BinaryOperatorNode(left: SemanticNode, operator: String, right: SemanticNode) extends SemanticNode
final case class TernaryOperatorNode(
    condition: SemanticNode,
    whenTrue: SemanticNode,
    whenFalse: SemanticNode
) extends SemanticNode
final case class AssignmentOperatorNode(
    target: SemanticNode,
    operator: String,
    value: SemanticNode
) extends SemanticNode
final case class DeclarationNode(
    valueType: String,
    name: String,
    initialValue: Option[SemanticNode]
) extends SemanticNode
final case class BlockNode(statements: Vector[SemanticNode]) extends SemanticNode
final case class IfNode(
    condition: SemanticNode,
    thenBranch: BlockNode,
    elseBranch: Option[BlockNode]
) extends SemanticNode
final case class WhileNode(condition: SemanticNode, body: BlockNode) extends SemanticNode
final case class ForNode(initializer: Option[SemanticNode], condition: Option[SemanticNode], update: Option[SemanticNode], body: BlockNode) extends SemanticNode
final case class ReturnNode(value: Option[SemanticNode]) extends SemanticNode
final case class FunctionParameterNode(valueType: String, name: String)
final case class FunctionDeclarationNode(returnType: String, name: String, parameters: Vector[FunctionParameterNode], body: BlockNode) extends SemanticNode


final class FunctionalSemanticTree:

    var args: HashMap[String, Value] = HashMap.empty
    var stack: HashMap[String, Value] = HashMap.empty
    var functions: HashMap[String, FunctionDeclarationNode] = HashMap.empty

    def this(args: HashMap[String, Value]) =
        this()
        this.set_args(args)

    def this(args: HashMap[String, Value], stack: HashMap[String, Value]) =
        this()
        this.set_args(args)
        this.set_stack(stack)

    def set_args(args: HashMap[String, Value]): Unit =
        this.args = args

    def set_stack(stack: HashMap[String, Value]): Unit =
        this.stack = stack
        
    var program: ProgramNode = ProgramNode(Vector.empty)

    def build(syntaxTree: FunctionalTree): ProgramNode =
        var semanticStatements: Vector[SemanticNode] = Vector.empty
        var statementIndex = 0

        while statementIndex < syntaxTree.statements.length do
            val semanticStatement = this.convert(syntaxTree.statements(statementIndex))
            semanticStatements = semanticStatements :+ semanticStatement
            semanticStatement match
                case function: FunctionDeclarationNode => this.functions(function.name) = function
                case _ =>
            statementIndex += 1

        this.program = ProgramNode(semanticStatements)
        this.program

    def convert(syntaxNode: Expr): SemanticNode =
        syntaxNode match
            case Variable(name) =>
                VariableNode(name)
            case Literal(value) =>
                NumericLiteralNode(value)
            case StringLiteral(value) =>
                StringLiteralNode(value)
            case Member(value, name) =>
                MemberAccessNode(this.convert(value), name)
            case Index(value, index) =>
                IndexAccessNode(this.convert(value), this.convert(index))
            case MultiIndex(value, indices) =>
                var semanticIndices: Vector[SemanticNode] = Vector.empty
                var index = 0
                while index < indices.length do
                    semanticIndices = semanticIndices :+ this.convert(indices(index))
                    index += 1
                MultiIndexAccessNode(this.convert(value), semanticIndices)
            case Call(function, arguments) =>
                var semanticArguments: Vector[SemanticNode] = Vector.empty
                var argumentIndex = 0

                while argumentIndex < arguments.length do
                    semanticArguments = semanticArguments :+ this.convert(arguments(argumentIndex))
                    argumentIndex += 1

                FunctionCallNode(this.convert(function), semanticArguments)
            case Parenthesized(value) =>
                // Parentheses have already established precedence in the syntax tree,
                // so the semantic tree can contain the grouped value directly.
                this.convert(value)
            case UnaryOp(operator, value) =>
                UnaryOperatorNode(operator, this.convert(value))
            case BinaryOp(left, operator, right) =>
                BinaryOperatorNode(this.convert(left), operator, this.convert(right))
            case Ternary(condition, whenTrue, whenFalse) =>
                TernaryOperatorNode(
                    this.convert(condition),
                    this.convert(whenTrue),
                    this.convert(whenFalse)
                )
            case Assign(left, operator, right) =>
                AssignmentOperatorNode(this.convert(left), operator, this.convert(right))
            case Declare(valueType, name, initialValue) =>
                DeclarationNode(valueType.name, name.name, initialValue.map(this.convert))
            case Block(statements) =>
                var semanticStatements: Vector[SemanticNode] = Vector.empty
                var statementIndex = 0

                while statementIndex < statements.length do
                    semanticStatements = semanticStatements :+ this.convert(statements(statementIndex))
                    statementIndex += 1

                BlockNode(semanticStatements)
            case IfStatement(condition, thenBranch, elseBranch) =>
                val semanticThen = this.convert(thenBranch).asInstanceOf[BlockNode]
                val semanticElse = elseBranch.map(branch => this.convert(branch).asInstanceOf[BlockNode])
                IfNode(this.convert(condition), semanticThen, semanticElse)
            case WhileStatement(condition, body) =>
                WhileNode(this.convert(condition), this.convert(body).asInstanceOf[BlockNode])
            case ForStatement(initializer, condition, update, body) =>
                ForNode(
                    initializer.map(this.convert),
                    condition.map(this.convert),
                    update.map(this.convert),
                    this.convert(body).asInstanceOf[BlockNode]
                )
            case ReturnStatement(value) =>
                ReturnNode(value.map(this.convert))
            case FunctionDeclaration(returnType, name, parameters, body) =>
                var semanticParameters: Vector[FunctionParameterNode] = Vector.empty
                var parameterIndex = 0
                while parameterIndex < parameters.length do
                    val parameter = parameters(parameterIndex)
                    semanticParameters = semanticParameters :+ FunctionParameterNode(parameter.valueType.name, parameter.name.name)
                    parameterIndex += 1
                FunctionDeclarationNode(
                    returnType.name,
                    name.name,
                    semanticParameters,
                    this.convert(body).asInstanceOf[BlockNode]
                )
