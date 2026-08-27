import fastparse.*, JavaWhitespace.*

sealed trait Expr
case class Ident(name: String) extends Expr
case class Num(value: Double) extends Expr
case class UnaryOp(op: String, value: Expr) extends Expr
case class BinaryOp(left: Expr, op: String, right: Expr) extends Expr
case class Assign(left: Ident, op: String, right: Expr) extends Expr
case class Call(function: Ident, arguments: Vector[Expr]) extends Expr
case class TypeName(name: String)
case class Declare(valueType: TypeName, name: Ident, initialValue: Option[Expr]) extends Expr
case class Block(statements: Vector[Expr]) extends Expr
case class IfStatement(condition: Expr, thenBranch: Block, elseBranch: Option[Block]) extends Expr
case class WhileStatement(condition: Expr, body: Block) extends Expr
case class ReturnStatement(value: Option[Expr]) extends Expr

case class FunctionalTree(statements: Vector[Expr]):
  def syntax_tree(): String =
    val output = new StringBuilder

    def line(depth: Int, text: String): Unit =
      var indent = 0
      while indent < depth do
        output.append("  ")
        indent += 1
      output.append(text).append('\n')

    def render(node: Expr, depth: Int, label: String): Unit =
      node match
        case Ident(name) =>
          line(depth, s"$label identifier \"$name\"")
        case Num(value) =>
          line(depth, s"$label number $value")
        case UnaryOp(operator, value) =>
          line(depth, s"$label unary_expression \"$operator\"")
          render(value, depth + 1, "value:")
        case BinaryOp(left, operator, right) =>
          line(depth, s"$label binary_expression \"$operator\"")
          render(left, depth + 1, "left:")
          render(right, depth + 1, "right:")
        case Assign(left, operator, right) =>
          line(depth, s"$label assignment_expression \"$operator\"")
          render(left, depth + 1, "left:")
          render(right, depth + 1, "right:")
        case Call(function, arguments) =>
          line(depth, s"$label call_expression")
          render(function, depth + 1, "function:")
          var argumentIndex = 0
          while argumentIndex < arguments.length do
            render(arguments(argumentIndex), depth + 1, s"argument[$argumentIndex]:")
            argumentIndex += 1
        case Declare(valueType, name, initialValue) =>
          line(depth, s"$label declaration \"${valueType.name}\"")
          render(name, depth + 1, "name:")
          initialValue match
            case Some(value) => render(value, depth + 1, "initial_value:")
            case None => line(depth + 1, "initial_value: none")
        case Block(statements) =>
          line(depth, s"$label block")
          var statementIndex = 0
          while statementIndex < statements.length do
            render(statements(statementIndex), depth + 1, s"statement[$statementIndex]:")
            statementIndex += 1
        case IfStatement(condition, thenBranch, elseBranch) =>
          line(depth, s"$label if_statement")
          render(condition, depth + 1, "condition:")
          render(thenBranch, depth + 1, "then:")
          elseBranch match
            case Some(branch) => render(branch, depth + 1, "else:")
            case None => line(depth + 1, "else: none")
        case WhileStatement(condition, body) =>
          line(depth, s"$label while_statement")
          render(condition, depth + 1, "condition:")
          render(body, depth + 1, "body:")
        case ReturnStatement(value) =>
          line(depth, s"$label return_statement")
          value match
            case Some(returnedValue) => render(returnedValue, depth + 1, "value:")
            case None => line(depth + 1, "value: none")

    line(0, "functional_tree")
    var statementIndex = 0
    while statementIndex < this.statements.length do
      render(this.statements(statementIndex), 1, s"statement[$statementIndex]:")
      statementIndex += 1

    output.result()

  def print_syntax_tree(): Unit =
    println(this.syntax_tree())


private def identifierStart[$: P]: P[Unit] = P(CharIn("a-zA-Z_"))
private def identifierRest[$: P]: P[Unit] = P(CharIn("a-zA-Z0-9_").repX)
private def identifierPart[$: P]: P[String] = P((identifierStart ~~ identifierRest).!)

def ident[$: P]: P[Ident] =
  P(identifierPart ~ ("." ~ identifierPart).rep).map { case (root, fields) =>
    Ident((root +: fields).mkString("."))
  }

def number[$: P]: P[Num] =
  P(
    (
      (CharIn("0-9").repX(1) ~~ ("." ~~ CharIn("0-9").repX).?) |
      ("." ~~ CharIn("0-9").repX(1))
    ) ~~ (CharIn("eE") ~~ StringIn("+", "-").? ~~ CharIn("0-9").repX(1)).?
  ).!.map(value => Num(value.toDouble))

def call[$: P]: P[Call] = P(
  ident ~ "(" ~ expr.rep(sep = ",") ~ ")"
).map { case (function, arguments) => Call(function, arguments.toVector) }

def factor[$: P]: P[Expr] = P(number | call | ident | "(" ~ expr ~ ")")

def unary[$: P]: P[Expr] = P(
  (StringIn("+", "-", "!").! ~ unary).map { case (op, value) => UnaryOp(op, value) } |
  factor
)

def mulDiv[$: P]: P[Expr] = P(unary ~ (StringIn("*", "/", "%").! ~ unary).rep).map {
  case (head, tail) => tail.foldLeft(head) { case (acc, (op, rhs)) => BinaryOp(acc, op, rhs) }
}

def addSub[$: P]: P[Expr] = P(mulDiv ~ (StringIn("+", "-").! ~ mulDiv).rep).map {
  case (head, tail) => tail.foldLeft(head) { case (acc, (op, rhs)) => BinaryOp(acc, op, rhs) }
}

def comparison[$: P]: P[Expr] = P(
  addSub ~ (StringIn("<=", ">=", "<", ">").! ~ addSub).rep
).map {
  case (head, tail) => tail.foldLeft(head) { case (acc, (op, rhs)) => BinaryOp(acc, op, rhs) }
}

def equality[$: P]: P[Expr] = P(
  comparison ~ (StringIn("==", "!=").! ~ comparison).rep
).map {
  case (head, tail) => tail.foldLeft(head) { case (acc, (op, rhs)) => BinaryOp(acc, op, rhs) }
}

def logicalAnd[$: P]: P[Expr] = P(equality ~ ("&&".! ~ equality).rep).map {
  case (head, tail) => tail.foldLeft(head) { case (acc, (op, rhs)) => BinaryOp(acc, op, rhs) }
}

def logicalOr[$: P]: P[Expr] = P(logicalAnd ~ ("||".! ~ logicalAnd).rep).map {
  case (head, tail) => tail.foldLeft(head) { case (acc, (op, rhs)) => BinaryOp(acc, op, rhs) }
}

def expr[$: P]: P[Expr] = P(
  (ident ~ StringIn("+=", "-=", "*=", "/=", "%=", "=").! ~ expr).map {
    case (id, op, value) => Assign(id, op, value)
  } |
  logicalOr
)

private def ifKeyword[$: P]: P[Unit] = P("if" ~~ !CharIn("a-zA-Z0-9_"))
private def elseKeyword[$: P]: P[Unit] = P("else" ~~ !CharIn("a-zA-Z0-9_"))
private def whileKeyword[$: P]: P[Unit] = P("while" ~~ !CharIn("a-zA-Z0-9_"))
private def returnKeyword[$: P]: P[Unit] = P("return" ~~ !CharIn("a-zA-Z0-9_"))

def declaration[$: P]: P[Declare] = P(
  identifierPart ~ identifierPart ~ ("=" ~ expr).?
).map { case (valueType, name, initialValue) =>
  Declare(TypeName(valueType), Ident(name), initialValue)
}

def block[$: P]: P[Block] = P(
  "{" ~ statement.rep ~ "}"
).map(statements => Block(statements.toVector))

def ifStatement[$: P]: P[IfStatement] = P(
  ifKeyword ~ "(" ~ expr ~ ")" ~ block ~
    (elseKeyword ~ (block | ifStatement.map(statement => Block(Vector(statement))))).?
).map { case (condition, thenBranch, elseBranch) =>
  IfStatement(condition, thenBranch, elseBranch)
}

def whileStatement[$: P]: P[WhileStatement] = P(
  whileKeyword ~ "(" ~ expr ~ ")" ~ block
).map { case (condition, body) => WhileStatement(condition, body) }

def returnStatement[$: P]: P[ReturnStatement] = P(
  returnKeyword ~ expr.?
).map(ReturnStatement.apply)

def statement[$: P]: P[Expr] = P(
  ifStatement |
  whileStatement |
  block |
  ((returnStatement | declaration | expr) ~ ";")
)

def program[$: P]: P[FunctionalTree] = P(
  Start ~ statement.rep(1) ~ End
).map(statements => FunctionalTree(statements.toVector))

private def completeExpression[$: P]: P[Expr] = P(expr ~ End)

def parseExpression(source: String): Parsed[Expr] =
  parse(source, completeExpression(using _))

def parseProgram(source: String): Parsed[FunctionalTree] =
  parse(source, program(using _))

// Usage:
// val Parsed.Success(ast, _) = parse("value += other * 2.0", expr(_))


class ParserTests:
  def test_actual_language_and_print_syntax_tree(): Unit =
    val source =
      """
        |Value result = add(value, other);
        |
        |if (result > limit) {
        |  result += other * 2.0;
        |} else {
        |  result = limit;
        |}
        |
        |while (result < target) {
        |  result += step;
        |}
        |
        |return result;
        |""".stripMargin

    parseProgram(source) match
      case Parsed.Success(tree, parsedTo) =>
        assert(parsedTo == source.length)
        assert(tree.statements.length == 4)
        assert(tree.statements(0).isInstanceOf[Declare])
        assert(tree.statements(1).isInstanceOf[IfStatement])
        assert(tree.statements(2).isInstanceOf[WhileStatement])
        assert(tree.statements(3).isInstanceOf[ReturnStatement])

        println("Parsed syntax tree:")
        tree.print_syntax_tree()

      case failure: Parsed.Failure =>
        throw new AssertionError(failure.trace().longMsg)
