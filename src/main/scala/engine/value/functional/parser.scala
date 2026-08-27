import fastparse._, SingleLineWhitespace._

sealed trait Expr
case class Ident(name: String) extends Expr
case class Num(value: Double) extends Expr
case class BinaryOp(left: Expr, op: String, right: Expr) extends Expr
case class Assign(left: Ident, op: String, right: Expr) extends Expr

def ident[_: P]: P[Ident] = P(CharsWhileIn("a-zA-Z_") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!.map(Ident)
def number[_: P]: P[Num] = P(CharsWhileIn("0-9") ~ ("." ~ CharsWhileIn("0-9")).?).!.map(s => Num(s.toDouble))
def factor[_: P]: P[Expr] = P(number | ident | "(" ~ expr ~ ")")

def mulDiv[_: P]: P[Expr] = P(factor ~ (StringIn("*", "/", "%").! ~ factor).rep).map {
  case (head, tail) => tail.foldLeft(head) { case (acc, (op, rhs)) => BinaryOp(acc, op, rhs) }
}

def addSub[_: P]: P[Expr] = P(mulDiv ~ (StringIn("+", "-").! ~ mulDiv).rep).map {
  case (head, tail) => tail.foldLeft(head) { case (acc, (op, rhs)) => BinaryOp(acc, op, rhs) }
}

def expr[_: P]: P[Expr] = P(
  (ident ~ StringIn("+=", "-=", "*=", "/=", "=").! ~ expr).map { case (id, op, e) => Assign(id, op, e) } |
  addSub
)

// Usage:
// val Parsed.Success(ast, _) = parse("value += other * 2.0", expr(_))

class ParserTests extends munit.FunSuite {
  test("parse simple number") {
    val Parsed.Success(value, _) = parse("123", number(_))
    assertEquals(value, 123.0)
  }
}