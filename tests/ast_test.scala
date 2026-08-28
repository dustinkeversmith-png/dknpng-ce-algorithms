
class FunctionalAstTests extends munit.FunSuite:
    test("build semantic tree from parser syntax"):
        val source =
            """
              |Value selected = particle.position[2];
              |selected = enabled ? selected + 1 : fallback;
              |return selected;
              |""".stripMargin

        parseProgram(source) match
            case fastparse.Parsed.Success(syntaxTree, _) =>
                val semanticTree = new FunctionalSemanticTree()
                val program = semanticTree.build(syntaxTree)

                assert(program.statements.length == 3)
                assert(program.statements(0).isInstanceOf[DeclarationNode])

                val declaration = program.statements(0).asInstanceOf[DeclarationNode]
                assert(declaration.valueType == "Value")
                assert(declaration.initialValue.contains(
                    IndexAccessNode(
                        MemberAccessNode(VariableNode("particle"), "position"),
                        NumericLiteralNode(2.0)
                    )
                ))

                val assignment = program.statements(1).asInstanceOf[AssignmentOperatorNode]
                assert(assignment.value.isInstanceOf[TernaryOperatorNode])
                assert(program.statements(2) == ReturnNode(Some(VariableNode("selected"))))

            case failure: fastparse.Parsed.Failure =>
                throw new AssertionError(failure.trace().longMsg)
