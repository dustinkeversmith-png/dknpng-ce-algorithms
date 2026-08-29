import value.*
import scala.collection.mutable.HashMap

class FunctionalSemanticTreeOperatorTests extends munit.FunSuite:

    test("# Now test building a operator from the fst and adding it to the registry, to using as an evaluation step "):
        val registry = new BaseTypes().registerAll()
        val positionType = new ValueType(
            "Position",
            Vector(3),
            Map("value" -> "int")
        )
        val particleType = new ValueType(
            "Particle",
            Map("position" -> positionType)
        )
        particleType.attach_registry(registry)
        assert(particleType.registry eq registry)
        assert(particleType.fields("position").registry eq registry)
        assert(particleType.fields("position").fields("value").registry eq registry)
        assert(positionType.registry eq registry)
        assert(positionType.fields("value").registry eq registry)

        val pairTypeA = new Value("PositionA", positionType)
        val pairTypeB = new Value("PositionB", positionType)
        pairTypeA.attach_registry(registry)
        pairTypeB.attach_registry(registry)

        pairTypeA(0) = 1
        pairTypeA(1) = 2
        pairTypeA(2) = 3
        pairTypeB(0) = 4
        pairTypeB(1) = 5
        pairTypeB(2) = 6

        val lengthOperator: OperatorFunction = (id, value, arguments) =>
            value.registry.caster.cast("int", value.shape.head.toDouble)

        pairTypeA.register_operator(
            FunctionalId("length", Map("a" -> "Position")),
            lengthOperator
        )

        val source =
            """
              Value add(Value a, Value b) {
                Value result;
                for (int i = 0; i < a.length(); i += 1) {
                  result[i] = a[i].operator("+", b[i]);
                }
                return result;
              }

              return add(a, b);
              """.stripMargin

        val syntaxTree = parseProgram(source) match
            case fastparse.Parsed.Success(tree, _) => tree
            case failure: fastparse.Parsed.Failure =>
                throw new AssertionError(failure.trace().longMsg)

        assert(syntaxTree.statements.head.isInstanceOf[FunctionDeclaration])
        val function = syntaxTree.statements.head.asInstanceOf[FunctionDeclaration]
        assert(function.returnType.name == "Value")
        assert(function.parameters == Vector(
            Parameter(TypeName("Value"), Variable("a")),
            Parameter(TypeName("Value"), Variable("b"))
        ))
        assert(function.body.statements(1).isInstanceOf[ForStatement])
        val recursiveLoop = function.body.statements(1).asInstanceOf[ForStatement]
        assert(recursiveLoop.body.statements.head == Assign(
            Index(Variable("result"), Variable("i")),
            "=",
            Call(
                Member(Index(Variable("a"), Variable("i")), "operator"),
                Vector(StringLiteral("+"), Index(Variable("b"), Variable("i")))
            )
        ))

        // Sets registry indexes the fields and then sets the values.
        val semanticTree = new FunctionalSemanticTree()
        val program = semanticTree.build(syntaxTree)
        assert(program.statements.head.isInstanceOf[FunctionDeclarationNode])
        assert(semanticTree.functions.contains("add"))

        val argumentTypes = Map("a" -> "Position", "b" -> "Position")
        registry.register_operator(FunctionalId("+", argumentTypes), program)

        val addedFields = pairTypeA.operator("+")(pairTypeB)

        assert(addedFields.t == "Position")
        assert(registry.caster.retrieve("int", addedFields(0)) == 5.0)
        assert(registry.caster.retrieve("int", addedFields(1)) == 7.0)
        assert(registry.caster.retrieve("int", addedFields(2)) == 9.0)

        assert(registry.caster.retrieve("int", pairTypeA(0)) == 1.0)
        assert(registry.caster.retrieve("int", pairTypeA(1)) == 2.0)
        assert(registry.caster.retrieve("int", pairTypeA(2)) == 3.0)

        // Validate that the program executed and evluated and addressable 
