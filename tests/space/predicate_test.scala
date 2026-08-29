/// Use our functional fst tree to compute programs to evaluate predicates and describe predicates for the value or value space

// Create a test using the predicate on values created to see if they meet the predicate

import value.*
import problem.space.*
import scala.collection.mutable.HashMap

class CompiledPredicateTests extends munit.FunSuite:

  test("compile an FST predicate and evaluate it against created Values"):
    val registry = new BaseTypes().registerAll()
    val particleType = new ValueType(
      "Particle",
      Map("mass" -> "double", "id" -> "int")
    )
    particleType.attach_registry(registry)

    val predicateSyntax = parseExpression("candidate.mass >= 0 && candidate.id != 0") match
      case fastparse.Parsed.Success(expression, _) => expression
      case failure: fastparse.Parsed.Failure =>
        throw new AssertionError(failure.trace().longMsg)

    val predicateTree = new FunctionalSemanticTree()
    val predicateProgram = predicateTree.build(
      FunctionalTree(Vector(ReturnStatement(Some(predicateSyntax))))
    )

    assert(predicateProgram.statements.head.isInstanceOf[ReturnNode])
    assert(predicateProgram.statements.head.asInstanceOf[ReturnNode].value.exists(
      _.isInstanceOf[BinaryOperatorNode]
    ))

    val compiledPredicate = Predicate(
      "non-negative mass and non-zero id",
      (candidate: Value) =>
        val arguments = HashMap[String, Value]("candidate" -> candidate)
        val result = new Evaluator(predicateTree, arguments).evaluate().getOrElse(
          throw new AssertionError("The compiled predicate did not return a Value")
        )
        result.registry.caster.retrieve(result.base_type_name(), result) != 0.0
    )

    val validParticle = new Value("validParticle", particleType)
    validParticle.attach_registry(registry)
    validParticle("mass") = 12.5
    validParticle("id") = 7

    val negativeMass = new Value("negativeMass", particleType)
    negativeMass.attach_registry(registry)
    negativeMass("mass") = -0.5
    negativeMass("id") = 7

    val missingId = new Value("missingId", particleType)
    missingId.attach_registry(registry)
    missingId("mass") = 12.5
    missingId("id") = 0

    assert(compiledPredicate(validParticle))
    assert(!compiledPredicate(negativeMass))
    assert(!compiledPredicate(missingId))
