// using a valuetype description and invariants create a constructive generator function or constructive grammar from the invaraitns
// This is the execution space of course


// Derive the generator grammar directly from the structural schema of the ValueType (e.g., field types, shapes, tails, and offsets).

// synthesis via constraint inversion (correct-by-construction generation)

// in the first test, testing building an inversion scheme to create the program automatically just from the invariant/s

// in the second test show examples of sampling and rejection and the inversion techniques

// Why Inverting the AST is NecessaryConstructive Target: Invariants are validation functions ($\mathcal{I}: \text{Value} \to \text{Boolean}$). Generators are synthesis functions ($\mathcal{G}: \text{Seed}/\text{Param} \to \text{Value}$).  The Inversion Step: You cannot just run an invariant FST to get data; you must transform the invariant AST into generator assignments before compiling it into the execution tree.

import value.*
import problem.space.*

class ConstructiveGeneratorTests extends munit.FunSuite:

  test("synthesize a generator from a complex invariant FST and recursive Value schema"):
    val registry = new BaseTypes().registerAll()
    val positionType = new ValueType("Position", Vector(2), Map("value" -> "int"))
    val candidateType = new ValueType(
      "Candidate",
      Map("position" -> positionType, "mass" -> "double")
    )
    candidateType.attach_registry(registry)

    val invariantSource =
      """
        byte admissible(Value candidate) {
          int x = candidate.position[0];
          int y = candidate.position[1];
          return (
            ((x * x + y * y == 25) ||
             (x + y == 7 && x % 2 == 0)) &&
            x != y &&
            candidate.mass == x + y
          );
        }

        return admissible(candidate);
        """.stripMargin
    val invariant = new Predicate("complex candidate invariant", invariantSource)

    val generator = new Generator(candidateType, invariant, maximum_models = 4)
    val integerDomain = Vector(-5, 0, 3, 4, 2, 5, 7, -4, -3, -2, -1, 1, 6, 8, 9)
    val doubleDomain = Vector(-5, 5, 7) ++ (-10 to 18).filterNot(number => Set(-5, 5, 7).contains(number))
    integerDomain.foreach(number => generator.register_number("int", number.toDouble))
    doubleDomain.foreach(number => generator.register_number("double", number.toDouble))
    generator.synthesize()

    assert(generator.schema.map(_.type_name) == Vector("int", "int", "double"))
    assert(generator.schema.map(_.offset) == Vector(0L, 4L, 8L))
    assert(generator.schema.map(_.length) == Vector(4L, 4L, 8L))
    assert(generator.schema.head.enclosing_shapes == Vector(Vector(2)))
    assert(generator.schema.head.enclosing_tails == Vector(Vector(4L)))
    assert(generator.models.nonEmpty)
    assert(generator.program.statements.head.isInstanceOf[FunctionDeclarationNode])
    assert(generator.syntax.syntax_tree().contains("If"))

    var seed = 0
    while seed < generator.models.length * 3 do
      val generated = generator.generate(s"generated_$seed")
      assert(invariant(generated))
      seed += 1

  test("compare rejection sampling with an automatically assembled complex-predicate generator"):
    val registry = new BaseTypes().registerAll()
    val vectorType = new ValueType("ComplexVector", Vector(2), Map("value" -> "int"))
    vectorType.attach_registry(registry)

    val invariantSource =
      """
        byte admissible(Value candidate) {
          int x = candidate[0];
          int y = candidate[1];
          return (
            (x * x + y * y == 25 ||
             (x + y == 7 && x % 2 == 0)) &&
            x != y
          );
        }

        return admissible(candidate);
        """.stripMargin
    val invariant = new Predicate("complex vector invariant", invariantSource)

    var rejectionAttempts = 0
    var rejectionAccepted = 0
    var rejectionRejected = 0
    while rejectionAccepted < 5 && rejectionAttempts < 500 do
      val candidate = new Value(s"sample_$rejectionAttempts", vectorType)
      candidate.attach_registry(registry)
      candidate(0) = (rejectionAttempts % 15) - 5
      candidate(1) = ((rejectionAttempts * 3) % 15) - 5
      rejectionAttempts += 1

      if invariant(candidate) then rejectionAccepted += 1
      else rejectionRejected += 1

    assert(rejectionAccepted == 5)

    val generator = new Generator(vectorType, invariant, maximum_models = 32)
    (-5 to 9).foreach(number => generator.register_number("int", number.toDouble))
    generator.synthesize()

    var constructiveAttempts = 0
    var constructiveAccepted = 0
    var constructiveRejected = 0
    while constructiveAttempts < 20 do
      val generated = generator.generate(s"constructive_$constructiveAttempts")
      constructiveAttempts += 1
      if invariant(generated) then constructiveAccepted += 1
      else constructiveRejected += 1

    assert(rejectionAttempts > rejectionAccepted)
    assert(rejectionRejected > 0)
    assert(constructiveAttempts == constructiveAccepted)
    assert(constructiveRejected == 0)
