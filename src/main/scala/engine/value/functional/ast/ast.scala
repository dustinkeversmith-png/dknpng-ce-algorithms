
trait FunctionalAst:

    // Stored memory references to the arguments
    def args: Map[String, MemoryRef]

    // Possibly any extra intermediary values created or assigned while executing in a local scope
    def stack: Map[String, Value]

    
    // Then probably each line has its own Ast, it executes mean while mutating the stack
    // Then moving on, and then pushes that all back into the type maybe?
    

sealed trait SemanticNode

final case class ProgramNode(statements: Vector[SemanticNode]) extends SemanticNode
final case class VariableNode(name: String) extends SemanticNode
final case class NumericLiteralNode(value: Double) extends SemanticNode
final case class StringLiteralNode(value: String) extends SemanticNode
final case class MemberAccessNode(value: SemanticNode, member: String) extends SemanticNode
final case class IndexAccessNode(value: SemanticNode, index: SemanticNode) extends SemanticNode
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
final case class ReturnNode(value: Option[SemanticNode]) extends SemanticNode


final class FunctionalSemanticTree(
    var args: Map[String, MemoryRef] = Map.empty,
    var stack: Map[String, Value] = Map.empty
) extends FunctionalAst:

    var program: ProgramNode = ProgramNode(Vector.empty)

    def build(syntaxTree: FunctionalTree): ProgramNode =
        var semanticStatements: Vector[SemanticNode] = Vector.empty
        var statementIndex = 0

        while statementIndex < syntaxTree.statements.length do
            semanticStatements = semanticStatements :+ this.convert(syntaxTree.statements(statementIndex))
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
            case ReturnStatement(value) =>
                ReturnNode(value.map(this.convert))


class FunctionalAstTests:
    def test_build_semantic_tree_from_parser_syntax(): Unit =
        val source =
            """
              |Value selected = particle.position[2];
              |selected = enabled ? selected + 1 : fallback;
              |return selected;
              |""".stripMargin

        parseProgram(source) match
            case fastparse.Parsed.Success(syntaxTree, _) =>
                val semanticTree = new FunctionalSemanticTree()
                val program = semanticTree.build(syntaxTree)

                assert(program.statements.length == 3)
                assert(program.statements(0).isInstanceOf[DeclarationNode])

                val declaration = program.statements(0).asInstanceOf[DeclarationNode]
                assert(declaration.valueType == "Value")
                assert(declaration.initialValue.contains(
                    IndexAccessNode(
                        MemberAccessNode(VariableNode("particle"), "position"),
                        NumericLiteralNode(2.0)
                    )
                ))

                val assignment = program.statements(1).asInstanceOf[AssignmentOperatorNode]
                assert(assignment.value.isInstanceOf[TernaryOperatorNode])
                assert(program.statements(2) == ReturnNode(Some(VariableNode("selected"))))

            case failure: fastparse.Parsed.Failure =>
                throw new AssertionError(failure.trace().longMsg)
