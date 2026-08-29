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

        args("a").operator("=")(semanticTree.registry.caster.cast("double", 3.0))
        args("b").operator("=")(semanticTree.registry.caster.cast("double", 4.0))
        args("limit").operator("=")(semanticTree.registry.caster.cast("double", 8.0))
        args("enabled").operator("=")(semanticTree.registry.caster.cast("byte", 1.0))
        args("output").operator("=")(semanticTree.registry.caster.cast("double", -100.0))
        mutableParticle.reference_member("position").reference_element(Array(1)).reference_member("value").operator("=")(semanticTree.registry.caster.cast("double", 99.0))


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

        val expected = semanticTree.registry.caster.cast("double", 14.0)
        assert(semanticTree.registry.caster.retrieve("byte", args("output").operator("equals")(expected)) == 1.0)
        assert(semanticTree.registry.caster.retrieve("byte", args("particle").reference_member("position").reference_element(Array(1)).reference_member("value").operator("equals")(expected)) == 1.0)
        assert(semanticTree.registry.caster.retrieve("byte", semanticTree.stack("result").operator("equals")(expected)) == 1.0)


    test("semantic tree can own an empty stack or receive an existing stack"):
        val args = HashMap.empty[String, Value]
        val ownedStackTree = new FunctionalSemanticTree(args)
        assert(ownedStackTree.stack.isEmpty)

        val passedStack = HashMap.empty[String, Value]
        val passedStackTree = new FunctionalSemanticTree(args, passedStack)
        assert(passedStackTree.stack eq passedStack)
