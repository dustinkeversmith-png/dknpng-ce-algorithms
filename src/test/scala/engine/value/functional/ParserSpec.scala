import fastparse.Parsed

class ParserTests extends munit.FunSuite:
  test("parse simple number"):
    val Parsed.Success(value, _) = parseExpression("123")
    assertEquals(value, Num(123.0))

  test("parse arithmetic using C-like precedence"):
    val Parsed.Success(value, _) = parseExpression("value + other * 2.0")
    assertEquals(
      value,
      BinaryOp(
        Ident("value"),
        "+",
        BinaryOp(Ident("other"), "*", Num(2.0))
      )
    )

  test("parse assignment, field references, unary expressions, and comparisons"):
    val Parsed.Success(value, _) = parseExpression("particle.mass += -other.mass * 2 >= limit")
    assertEquals(
      value,
      Assign(
        Ident("particle.mass"),
        "+=",
        BinaryOp(
          BinaryOp(UnaryOp("-", Ident("other.mass")), "*", Num(2.0)),
          ">=",
          Ident("limit")
        )
      )
    )

  test("parse a newline-delimited program without type names or semicolons"):
    val source =
      """
        |// Arguments and intermediary values need no type declarations.
        |result = value + other
        |other = result * 2.0
        |value += other
        |""".stripMargin

    val Parsed.Success(tree, _) = parseProgram(source)
    assertEquals(tree.statements.length, 3)
    assertEquals(
      tree.statements.head,
      Assign(Ident("result"), "=", BinaryOp(Ident("value"), "+", Ident("other")))
    )

  test("reject semicolons and C++ type declarations"):
    assert(parseProgram("result = value + other;").isInstanceOf[Parsed.Failure])
    val typedDeclaration = parseProgram("double result = value + other")
    assert(clue(typedDeclaration).isInstanceOf[Parsed.Failure])
