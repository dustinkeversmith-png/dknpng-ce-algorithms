import fastparse.*, JavaWhitespace.*

sealed trait Expr
case class Variable(name: String) extends Expr
case class Literal(value: Double) extends Expr
case class StringLiteral(value: String) extends Expr
case class Member(value: Expr, name: String) extends Expr
case class Index(value: Expr, index: Expr) extends Expr
case class Call(function: Expr, arguments: Vector[Expr]) extends Expr
case class Parenthesized(value: Expr) extends Expr
case class UnaryOp(op: String, value: Expr) extends Expr
case class BinaryOp(left: Expr, op: String, right: Expr) extends Expr
case class Ternary(condition: Expr, whenTrue: Expr, whenFalse: Expr) extends Expr
case class Assign(left: Expr, op: String, right: Expr) extends Expr
case class TypeName(name: String)
case class Declare(valueType: TypeName, name: Variable, initialValue: Option[Expr]) extends Expr
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

    def literalText(value: Double): String =
      if value.isWhole then value.toLong.toString
      else value.toString

    def render(node: Expr, depth: Int, label: String): Unit =
      node match
        case Variable(name) =>
          line(depth, s"$label Variable(\"$name\")")
        case Literal(value) =>
          line(depth, s"$label Literal(${literalText(value)})")
        case StringLiteral(value) =>
          line(depth, s"$label StringLiteral(\"$value\")")
        case Member(value, name) =>
          line(depth, s"$label Member")
          render(value, depth + 1, "value:")
          line(depth + 1, s"member: \"$name\"")
        case Index(value, index) =>
          line(depth, s"$label Index")
          render(value, depth + 1, "value:")
          render(index, depth + 1, "index:")
        case Call(function, arguments) =>
          line(depth, s"$label Call")
          render(function, depth + 1, "function:")
          var argumentIndex = 0
          while argumentIndex < arguments.length do
            render(arguments(argumentIndex), depth + 1, s"argument[$argumentIndex]:")
            argumentIndex += 1
        case Parenthesized(value) =>
          line(depth, s"$label Parenthesized")
          render(value, depth + 1, "value:")
        case UnaryOp(operator, value) =>
          line(depth, s"$label UnaryOperator(\"$operator\")")
          render(value, depth + 1, "value:")
        case BinaryOp(left, operator, right) =>
          line(depth, s"$label BinaryOperator(\"$operator\")")
          render(left, depth + 1, "left:")
          render(right, depth + 1, "right:")
        case Ternary(condition, whenTrue, whenFalse) =>
          line(depth, s"$label Ternary")
          render(condition, depth + 1, "condition:")
          render(whenTrue, depth + 1, "true:")
          render(whenFalse, depth + 1, "false:")
        case Assign(left, operator, right) =>
          line(depth, s"$label Assignment(\"$operator\")")
          render(left, depth + 1, "left:")
          render(right, depth + 1, "right:")
        case Declare(valueType, name, initialValue) =>
          line(depth, s"$label Declaration(\"${valueType.name}\")")
          render(name, depth + 1, "name:")
          initialValue match
            case Some(value) => render(value, depth + 1, "initial_value:")
            case None => line(depth + 1, "initial_value: none")
        case Block(statements) =>
          line(depth, s"$label Block")
          var statementIndex = 0
          while statementIndex < statements.length do
            render(statements(statementIndex), depth + 1, s"statement[$statementIndex]:")
            statementIndex += 1
        case IfStatement(condition, thenBranch, elseBranch) =>
          line(depth, s"$label If")
          render(condition, depth + 1, "condition:")
          render(thenBranch, depth + 1, "then:")
          elseBranch match
            case Some(branch) => render(branch, depth + 1, "else:")
            case None => line(depth + 1, "else: none")
        case WhileStatement(condition, body) =>
          line(depth, s"$label While")
          render(condition, depth + 1, "condition:")
          render(body, depth + 1, "body:")
        case ReturnStatement(value) =>
          line(depth, s"$label Return")
          value match
            case Some(returnedValue) => render(returnedValue, depth + 1, "value:")
            case None => line(depth + 1, "value: none")

    line(0, "FunctionalTree")
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

def ident[$: P]: P[Variable] =
  P(identifierPart.map(Variable.apply))

def number[$: P]: P[Literal] =
  P(
    (
      (CharIn("0-9").repX(1) ~~ ("." ~~ CharIn("0-9").repX).?) |
      ("." ~~ CharIn("0-9").repX(1))
    ) ~~ (CharIn("eE") ~~ StringIn("+", "-").? ~~ CharIn("0-9").repX(1)).?
  ).!.map(value => Literal(value.toDouble))

private def stringPart[$: P]: P[String] = P(
  CharsWhile(character => character != '"' && character != '\\', 1).! |
  ("\\" ~~ AnyChar).!
)

def stringLiteral[$: P]: P[StringLiteral] = P(
  "\"" ~~ stringPart.repX ~~ "\""
).map { parts =>
  val value = new StringBuilder
  var partIndex = 0

  while partIndex < parts.length do
    val part = parts(partIndex)
    if part.length == 2 && part.charAt(0) == '\\' then
      part.charAt(1) match
        case 'n' => value.append('\n')
        case 'r' => value.append('\r')
        case 't' => value.append('\t')
        case '"' => value.append('"')
        case '\\' => value.append('\\')
        case escaped => value.append(escaped)
    else value.append(part)
    partIndex += 1

  StringLiteral(value.result())
}

def primary[$: P]: P[Expr] = P(
  number |
  stringLiteral |
  ident |
  ("(" ~ expr ~ ")").map(Parenthesized.apply)
)

private def memberSuffix[$: P]: P[Expr => Expr] = P(
  "." ~ identifierPart
).map(memberName => (value: Expr) => Member(value, memberName))

private def indexSuffix[$: P]: P[Expr => Expr] = P(
  "[" ~ expr ~ "]"
).map(index => (value: Expr) => Index(value, index))

private def callSuffix[$: P]: P[Expr => Expr] = P(
  "(" ~ expr.rep(sep = ",") ~ ")"
).map(arguments => (function: Expr) => Call(function, arguments.toVector))

def postfix[$: P]: P[Expr] = P(
  primary ~ (memberSuffix | indexSuffix | callSuffix).rep
).map { case (initial, suffixes) =>
  suffixes.foldLeft(initial) { case (value, suffix) => suffix(value) }
}

def call[$: P]: P[Expr] = P(postfix)

def factor[$: P]: P[Expr] = P(postfix)

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

def ternary[$: P]: P[Expr] = P(
  logicalOr ~ ("?" ~ expr ~ ":" ~ expr).?
).map {
  case (condition, Some((whenTrue, whenFalse))) => Ternary(condition, whenTrue, whenFalse)
  case (value, None) => value
}

def expr[$: P]: P[Expr] = P(
  (postfix ~ StringIn("+=", "-=", "*=", "/=", "%=", "=").! ~ expr).map {
    case (left, op, right) => Assign(left, op, right)
  } |
  ternary
)

private def ifKeyword[$: P]: P[Unit] = P("if" ~~ !CharIn("a-zA-Z0-9_"))
private def elseKeyword[$: P]: P[Unit] = P("else" ~~ !CharIn("a-zA-Z0-9_"))
private def whileKeyword[$: P]: P[Unit] = P("while" ~~ !CharIn("a-zA-Z0-9_"))
private def returnKeyword[$: P]: P[Unit] = P("return" ~~ !CharIn("a-zA-Z0-9_"))

def declaration[$: P]: P[Declare] = P(
  identifierPart ~ identifierPart ~ ("=" ~ expr).?
).map { case (valueType, name, initialValue) =>
  Declare(TypeName(valueType), Variable(name), initialValue)
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
  def test_all_expression_syntax_and_print_syntax_tree(): Unit =
    val indexedMember = parseExpression("particle.position[2]")
    assert(
      indexedMember == Parsed.Success(
        Index(Member(Variable("particle"), "position"), Literal(2.0)),
        "particle.position[2]".length
      )
    )

    val source =
      """
        |Value result = compute(particle.position[2], "mass");
        |result = (particle.mass + 2.0) * scale;
        |result = enabled && result >= limit ? result : fallback;
        |
        |if (result != 0 && !disabled) {
        |  particle.position[2] = result;
        |} else {
        |  result = 0;
        |}
        |
        |return result;
        |""".stripMargin

    parseProgram(source) match
      case Parsed.Success(tree, parsedTo) =>
        assert(parsedTo == source.length)
        assert(tree.statements.length == 5)
        assert(tree.statements(0).isInstanceOf[Declare])
        assert(tree.statements(1).isInstanceOf[Assign])
        assert(tree.statements(2).asInstanceOf[Assign].right.isInstanceOf[Ternary])
        assert(tree.statements(3).isInstanceOf[IfStatement])
        assert(tree.statements(4).isInstanceOf[ReturnStatement])

        println("Parsed syntax tree:")
        tree.print_syntax_tree()

      case failure: Parsed.Failure =>
        throw new AssertionError(failure.trace().longMsg)
