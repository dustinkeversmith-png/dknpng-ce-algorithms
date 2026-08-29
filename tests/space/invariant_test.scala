// use our compiled predicates for our fst to compose into a singular invariant
//Represent individual invariants as compiled AST predicates ($P_1, P_2$)
// Compose them algebraically (e.g., via logical conjunction && or sequential validation chains) into a unified invariant node before building the evaluation tree.

import value.*
import problem.space.*
import scala.collection.mutable.HashMap

class CompiledInvariantTests extends munit.FunSuite:

  test("compose compiled predicate ASTs into one invariant before semantic evaluation"):
    val registry = new BaseTypes().registerAll()
    val measurementType = new ValueType(
      "Measurement",
      Map("amount" -> "double")
    )
    measurementType.attach_registry(registry)

    val lowerBoundSyntax = parseExpression("candidate.amount >= 0") match
      case fastparse.Parsed.Success(expression, _) => expression
      case failure: fastparse.Parsed.Failure =>
        throw new AssertionError(failure.trace().longMsg)

    val upperBoundSyntax = parseExpression("candidate.amount <= 10") match
      case fastparse.Parsed.Success(expression, _) => expression
      case failure: fastparse.Parsed.Failure =>
        throw new AssertionError(failure.trace().longMsg)

    val lowerBoundTree = new FunctionalSemanticTree()
    lowerBoundTree.build(FunctionalTree(Vector(ReturnStatement(Some(lowerBoundSyntax)))))
    val upperBoundTree = new FunctionalSemanticTree()
    upperBoundTree.build(FunctionalTree(Vector(ReturnStatement(Some(upperBoundSyntax)))))

    assert(lowerBoundTree.program.statements.head == ReturnNode(Some(
      BinaryOperatorNode(MemberAccessNode(VariableNode("candidate"), "amount"), ">=", NumericLiteralNode(0.0))
    )))
    assert(upperBoundTree.program.statements.head == ReturnNode(Some(
      BinaryOperatorNode(MemberAccessNode(VariableNode("candidate"), "amount"), "<=", NumericLiteralNode(10.0))
    )))

    val unifiedInvariantSyntax = BinaryOp(lowerBoundSyntax, "&&", upperBoundSyntax)
    val unifiedInvariantTree = new FunctionalSemanticTree()
    unifiedInvariantTree.build(
      FunctionalTree(Vector(ReturnStatement(Some(unifiedInvariantSyntax))))
    )

    assert(unifiedInvariantTree.program.statements.head == ReturnNode(Some(
      BinaryOperatorNode(
        BinaryOperatorNode(MemberAccessNode(VariableNode("candidate"), "amount"), ">=", NumericLiteralNode(0.0)),
        "&&",
        BinaryOperatorNode(MemberAccessNode(VariableNode("candidate"), "amount"), "<=", NumericLiteralNode(10.0))
      )
    )))

    def compiledPredicate(name: String, tree: FunctionalSemanticTree): Predicate =
      Predicate(
        name,
        (candidate: Value) =>
          val result = new Evaluator(
            tree,
            HashMap[String, Value]("candidate" -> candidate)
          ).evaluate().getOrElse(
            throw new AssertionError(s"Compiled predicate '$name' did not return a Value")
          )
          result.registry.caster.retrieve(result.base_type_name(), result) != 0.0
      )

    val lowerInvariant = Invariant(
      "amount >= 0",
      compiledPredicate("P1", lowerBoundTree),
      _ => "amount was below zero"
    )
    val upperInvariant = Invariant(
      "amount <= 10",
      compiledPredicate("P2", upperBoundTree),
      _ => "amount was above ten"
    )
    val sequentialInvariant = lowerInvariant && upperInvariant
    val unifiedInvariant = Invariant(
      "P1 && P2",
      compiledPredicate("P1 && P2", unifiedInvariantTree),
      value => sequentialInvariant.violationMessage(value)
    )

    def measurement(name: String, amount: Double): Value =
      val value = new Value(name, measurementType)
      value.attach_registry(registry)
      value("amount") = amount
      value

    val inside = measurement("inside", 4.0)
    val below = measurement("below", -1.0)
    val above = measurement("above", 11.0)

    assert(unifiedInvariant.holds(inside))
    assert(!unifiedInvariant.holds(below))
    assert(!unifiedInvariant.holds(above))
    assert(sequentialInvariant.violationMessage(below) == "amount was below zero")
    assert(sequentialInvariant.violationMessage(above) == "amount was above ten")
