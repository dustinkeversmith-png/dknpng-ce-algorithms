// Test
// parse a program using variable names, and testing all the different operators
// convert the tree into a semantic nodal tree and then evaluate on a variable args passed into it,
// measure the accuracy by checking the mutations and execution.

import value.*
import scala.collection.mutable.HashMap

class FunctionalSemanticTreeIntegrationTests extends munit.FunSuite:

    test("build simple c like domain registry with arrays, vectors, .length, and all that then build a test program over the domain"):
        val registry = new BaseTypes().registerAll()
        val vectorType = new ValueType(
            "Vector",
            Vector(5),
            Map("value" -> "int")
        )
        vectorType.attach_registry(registry)

        val values = new Value("numbers", vectorType)
        values.attach_registry(registry)
        values(0) = 5
        values(1) = 1
        values(2) = 4
        values(3) = 2
        values(4) = 3

        val lengthOperator: OperatorFunction = (id, value, arguments) =>
            value.registry.caster.cast("int", value.shape.head.toDouble)

        values.register_operator(
            FunctionalId("length", Map("a" -> "Vector")),
            lengthOperator
        )

        val source =
            """
              Vector sort(Vector values) {
                values[0]["value"] = values[0]["value"];
                for (int i = 0; i < values.length(); i += 1) {
                  for (int j = 0; j < values.length() - 1; j += 1) {
                    if (values[j] > values[j + 1]) {
                      int temporary = values[j];
                      values[j] = values[j + 1];
                      values[j + 1] = temporary;
                    }
                  }
                }
                return values;
              }

              return sort(values);
              """.stripMargin

        val syntaxTree = parseProgram(source) match
            case fastparse.Parsed.Success(tree, _) => tree
            case failure: fastparse.Parsed.Failure =>
                throw new AssertionError(failure.trace().longMsg)

        assert(syntaxTree.statements.head.isInstanceOf[FunctionDeclaration])
        val function = syntaxTree.statements.head.asInstanceOf[FunctionDeclaration]
        assert(function.returnType.name == "Vector")
        assert(function.parameters == Vector(Parameter(TypeName("Vector"), Variable("values"))))
        assert(function.body.statements(1).isInstanceOf[ForStatement])

        val args = HashMap[String, Value]("values" -> values)
        val semanticTree = new FunctionalSemanticTree()
        val program = semanticTree.build(syntaxTree)
        assert(program.statements.head.isInstanceOf[FunctionDeclarationNode])
        assert(semanticTree.functions.contains("sort"))

        val returned = new Evaluator(semanticTree, args).evaluate().getOrElse(
            throw new AssertionError("The sorting function did not return a Value")
        )

        assert(returned.t == "Vector")
        assert(returned.memory eq values.memory)
        assert(registry.caster.retrieve("int", returned(0)) == 1.0)
        assert(registry.caster.retrieve("int", returned(1)) == 2.0)
        assert(registry.caster.retrieve("int", returned(2)) == 3.0)
        assert(registry.caster.retrieve("int", returned(3)) == 4.0)
        assert(registry.caster.retrieve("int", returned(4)) == 5.0)

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

        val registry = new BaseTypes().registerAll()
        positionType.attach_registry(registry)
        mutableParticle.attach_registry(registry)

        val args = HashMap[String, Value](
            "a" -> new Value("a", Vector.empty, Map("value" -> "double")),
            "b" -> new Value("b", Vector.empty, Map("value" -> "double")),
            "limit" -> new Value("limit", Vector.empty, Map("value" -> "double")),
            "enabled" -> new Value("enabled", Vector.empty, Map("value" -> "byte")),
            "output" -> new Value("output", Vector.empty, Map("value" -> "double")),
            "particle" -> mutableParticle
        )

        val semanticTree = new FunctionalSemanticTree()
        val program = semanticTree.build(syntaxTree)

        val argumentValues = args.valuesIterator
        while argumentValues.hasNext do argumentValues.next().attach_registry(registry)

        args("a").operator("=")(registry.caster.cast("double", 3.0))
        args("b").operator("=")(registry.caster.cast("double", 4.0))
        args("limit").operator("=")(registry.caster.cast("double", 8.0))
        args("enabled").operator("=")(registry.caster.cast("byte", 1.0))
        args("output").operator("=")(registry.caster.cast("double", -100.0))
        mutableParticle.reference_member("position").reference_element(Array(1)).reference_member("value").operator("=")(registry.caster.cast("double", 99.0))


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


        val evaluator = new Evaluator(semanticTree, args)

        val returned = evaluator.evaluate()

        val expected = registry.caster.cast("double", 14.0)
        assert(registry.caster.retrieve("byte", args("output").operator("equals")(expected)) == 1.0)
        assert(registry.caster.retrieve("byte", args("particle").reference_member("position").reference_element(Array(1)).reference_member("value").operator("equals")(expected)) == 1.0)
        assert(registry.caster.retrieve("byte", evaluator.stack("result").operator("equals")(expected)) == 1.0)


    test("evaluator can own an empty stack or receive an existing stack"):
        val args = HashMap.empty[String, Value]
        val semanticTree = new FunctionalSemanticTree()
        val ownedStackEvaluator = new Evaluator(semanticTree, args)
        assert(ownedStackEvaluator.stack.isEmpty)

        val passedStack = HashMap.empty[String, Value]
        val passedStackEvaluator = new Evaluator(semanticTree, args, passedStack)
        assert(passedStackEvaluator.stack eq passedStack)
