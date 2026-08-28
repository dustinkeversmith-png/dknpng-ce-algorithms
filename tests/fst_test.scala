// Test
// parse a program using variable names, and testing all the different operators
// convert the tree into a semantic nodal tree and then evaluate on a variable args passed into it,
// measure the accuracy by checking the mutations and execution.

import scala.collection.mutable.HashMap

class FunctionalSemanticTreeIntegrationTests extends munit.FunSuite:
    test("parse, build the finalized nodal tree, and evaluate mutable Value arguments"):
        val source =
            """
              double result = a + b * 2;
              result += 4;
              result -= 1;
              result *= 2;
              result /= 2;
              result %= 20;
              particle.position[1].value = result;
              if ((result > limit && enabled != 0) || !(result == 0)) {
                output = result >= 10 ? result : limit;
              } else {
                output = -1;
              }
              return output;
              """.stripMargin

        val syntaxTree = parseProgram(source) match
            case fastparse.Parsed.Success(tree, _) => tree
            case failure: fastparse.Parsed.Failure =>
                throw new AssertionError(failure.trace().longMsg)

        val positionType = new ValueType(
            "Position",
            Vector(3),
            Map("value" -> "double")
        )

        val mutableParticle = new Value("particle", Vector.empty, Map("position" -> positionType))

        val args = HashMap[String, Value](
            "a" -> new Value("a", Vector.empty, Map("value" -> "double")),
            "b" -> new Value("b", Vector.empty, Map("value" -> "double")),
            "limit" -> new Value("limit", Vector.empty, Map("value" -> "double")),
            "enabled" -> new Value("enabled", Vector.empty, Map("value" -> "byte")),
            "output" -> new Value("output", Vector.empty, Map("value" -> "double")),
            "particle" -> mutableParticle
        )

        val semanticTree = new FunctionalSemanticTree(args)
        val program = semanticTree.build(syntaxTree)


        // Asserting program correctness.
        assert(program.statements.length == 9)
        assert(program.statements.head == DeclarationNode(
            "double",
            "result",
            Some(BinaryOperatorNode(
                VariableNode("a"),
                "+",
                BinaryOperatorNode(VariableNode("b"), "*", NumericLiteralNode(2.0))
            ))
        ))
        assert(program.statements(6).isInstanceOf[AssignmentOperatorNode])
        assert(program.statements(7).isInstanceOf[IfNode])
        assert(program.statements.last == ReturnNode(Some(VariableNode("output"))))


        val evaluator = new Evaluator(semanticTree)

        val returned = evaluator.evaluate()

        (args["output"].operators("equals")(14.0))
        (args["particle"][1].operators("equals")(14.0))
        semanticTree.stack("result").operators("equals")(14.0)


    test("semantic tree can own an empty stack or receive an existing stack"):
        val args = HashMap.empty[String, Value]
        val ownedStackTree = new FunctionalSemanticTree(args)
        assert(ownedStackTree.stack.isEmpty)

        val passedStack = HashMap.empty[String, Value]
        val passedStackTree = new FunctionalSemanticTree(args, passedStack)
        assert(passedStackTree.stack eq passedStack)
