
import value.*
import fastparse.Parsed

class ParserTests extends munit.FunSuite:
  test("all expression syntax and print syntax tree"):
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
