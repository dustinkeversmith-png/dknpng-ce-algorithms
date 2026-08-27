import fastparse.*, SingleLineWhitespace.*

sealed trait Expr
case class Ident(name: String) extends Expr
case class Num(value: Double) extends Expr
case class UnaryOp(op: String, value: Expr) extends Expr
case class BinaryOp(left: Expr, op: String, right: Expr) extends Expr
case class Assign(left: Ident, op: String, right: Expr) extends Expr
case class FunctionalTree(statements: Vector[Expr])

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

def factor[$: P]: P[Expr] = P(number | ident | "(" ~ expr ~ ")")

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

private def newLine[$: P]: P[Unit] = P("\r\n" | "\n" | "\r")
private def lineComment[$: P]: P[Unit] =
  P("//" ~ CharsWhile(character => character != '\r' && character != '\n', 0))
private def blankLine[$: P]: P[Unit] = P(lineComment.? ~ newLine)

def program[$: P]: P[FunctionalTree] = P(
  Start ~ blankLine.rep ~
    (expr ~ lineComment.? ~ (newLine | End) ~ blankLine.rep).rep(1) ~
    End
).map(statements => FunctionalTree(statements.toVector))

private def completeExpression[$: P]: P[Expr] = P(expr ~ End)

def parseExpression(source: String): Parsed[Expr] =
  parse(source, completeExpression(using _))

def parseProgram(source: String): Parsed[FunctionalTree] =
  parse(source, program(using _))

// Usage:
// val Parsed.Success(ast, _) = parse("value += other * 2.0", expr(_))
