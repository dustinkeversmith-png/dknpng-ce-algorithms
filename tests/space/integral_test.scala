import value.*
import problem.space.*
import scala.collection.immutable.ListMap
import scala.collection.mutable.LinkedHashMap

class IntegralSpaceStressTests extends munit.FunSuite:

  def registerMetadataOperators(registry: TypeRegistry, typeNames: Vector[String]): Unit =
    typeNames.foreach { typeName =>
      def metadata(name: String, measurement: Value => Double): Unit =
        registry.register_operator(
          typeName,
          FunctionalId(name, ListMap("a" -> typeName)),
          (id, value, arguments) => registry.caster.cast("int", measurement(value))
        )

      metadata("length", value => if value.shape.isEmpty then 0.0 else value.shape.product.toDouble)
      metadata("rank", value => value.shape.length.toDouble)
      metadata("offset", value => value.memory_offset.toDouble)
      metadata("byte_size", value => value.total_size.toDouble)
      metadata("total_size", value => value.total_size.toDouble)
    }

  def generatorFor(
    valueType: ValueType,
    predicate: Predicate,
    doubles: Vector[Double],
    integers: Vector[Int],
    maximumModels: Int = 8
  ): Generator =
    val generator = new Generator(valueType, predicate, maximumModels)
    doubles.foreach(number => generator.register_number("double", number))
    integers.foreach(number => generator.register_number("int", number.toDouble))
    generator.synthesize()
    generator

  test("validate parsed structural invariants and synthesize from the same compiled program"):
    val registry = new BaseTypes().registerAll()
    registerMetadataOperators(registry, Vector("Coordinates", "Momentum", "StructuralState", "double", "int"))

    def stateType(coordinateLength: Int, momentumLength: Int): ValueType =
      val result = new ValueType(
        "StructuralState",
        LinkedHashMap[String, String | ValueType](
          "coordinates" -> new ValueType("Coordinates", Vector(coordinateLength), Map("value" -> "double")),
          "momentum" -> new ValueType("Momentum", Vector(momentumLength), Map("value" -> "double")),
          "mass" -> "double",
          "particleCount" -> "int"
        )
      )
      result.attach_registry(registry)
      result

    val designatedType = stateType(3, 3)
    val shapeProgram =
      """
        return state.coordinates.rank() == 1 &&
          state.momentum.rank() == 1 &&
          state.coordinates.length() > 0 &&
          state.coordinates.length() == state.momentum.length() &&
          state.coordinates.length() >= 2 &&
          state.coordinates.length() <= 4;
        """.stripMargin
    val memoryProgram =
      """
        return state.coordinates.offset() == 0 &&
          state.momentum.offset() == state.coordinates.byte_size() &&
          state.mass.offset() == state.momentum.offset() + state.momentum.byte_size() &&
          state.particleCount.offset() == state.mass.offset() + state.mass.byte_size() &&
          state.total_size() == state.particleCount.offset() + state.particleCount.byte_size();
        """.stripMargin

    val shapeInvariant = Invariant(
      "non-empty aligned variable dimensions",
      new Predicate("shape program", shapeProgram, "state")
    )
    val memoryInvariant = Invariant(
      "contiguous recursive memory offsets",
      new Predicate("memory program", memoryProgram, "state")
    )
    val structuralInvariant = shapeInvariant && memoryInvariant
    val generator = generatorFor(designatedType, structuralInvariant.predicate, Vector(0.0), Vector(0), 1)

    val structuralSpace = new Space:
      def description = "parsed structural schema space"
      def value_type = designatedType
      def structural_invariants = List(shapeInvariant, memoryInvariant)
      def semantic_invariants = Nil
      val generator = IntegralSpaceStressTests.this.generatorFor(
        designatedType,
        structuralInvariant.predicate,
        Vector(0.0),
        Vector(0),
        1
      )

    val valid = generator.generate("structural_generated")
    val generatedBySpace = structuralSpace.generate()
    val mismatched = new Value("mismatched", stateType(2, 3))
    mismatched.attach_registry(registry)

    assert(structuralSpace.contains(valid))
    assert(structuralSpace.contains(generatedBySpace))
    assert(!shapeInvariant.holds(mismatched))
    assert(!structuralSpace.contains(mismatched))
    assert(generator.program.statements.head.isInstanceOf[FunctionDeclarationNode])

  test("validate a complex parsed semantic and primitive-domain invariant with constructive inversion"):
    val registry = new BaseTypes().registerAll()
    val stateType = new ValueType(
      "SemanticState",
      LinkedHashMap[String, String | ValueType](
        "coordinates" -> new ValueType("Coordinates", Vector(2), Map("value" -> "double")),
        "momentum" -> new ValueType("Momentum", Vector(2), Map("value" -> "double")),
        "mass" -> "double",
        "particleCount" -> "int"
      )
    )
    stateType.attach_registry(registry)

    val invariantProgram =
      """
        byte physically_valid(Value state) {
          double x = state.coordinates[0];
          double y = state.coordinates[1];
          double conserved = state.momentum[0] + state.momentum[1];
          return x * x + y * y == 25 &&
            state.mass == conserved &&
            state.mass >= 0 &&
            state.particleCount >= 0 &&
            state.particleCount <= 2147483647 &&
            x >= -1000 && x <= 1000 && y >= -1000 && y <= 1000;
        }
        return physically_valid(state);
        """.stripMargin
    val semanticInvariant = Invariant(
      "circle conservation non-negativity and primitive domains",
      new Predicate("physical program", invariantProgram, "state")
    )
    val generator = generatorFor(
      stateType,
      semanticInvariant.predicate,
      Vector(3.0, 4.0, 1.0, 2.0, 0.0),
      Vector(0, 2),
      6
    )

    val semanticSpace = new Space:
      def description = "compiled physical semantic space"
      def value_type = stateType
      def structural_invariants = Nil
      def semantic_invariants = List(semanticInvariant)
      val generator = IntegralSpaceStressTests.this.generatorFor(
        stateType,
        semanticInvariant.predicate,
        Vector(3.0, 4.0, 1.0, 2.0, 0.0),
        Vector(0, 2),
        6
      )

    var generatedIndex = 0
    while generatedIndex < 12 do
      assert(semanticSpace.contains(semanticSpace.generate()))
      generatedIndex += 1

    val invalid = generator.generate("invalid_after_mutation")
    invalid("mass") = -1.0
    assert(!semanticInvariant.holds(invalid))

  test("compose parsed predicates with conjunction disjunction and negation before synthesis"):
    val registry = new BaseTypes().registerAll()
    val stateType = new ValueType(
      "CompositionState",
      LinkedHashMap[String, String | ValueType](
        "x" -> "int",
        "y" -> "int",
        "mass" -> "int"
      )
    )
    stateType.attach_registry(registry)

    val circle = new Predicate(
      "circle",
      "return candidate.x * candidate.x + candidate.y * candidate.y == 25;"
    )
    val positiveMass = new Predicate(
      "positive mass",
      "return candidate.mass > 0;"
    )
    val lowMass = new Predicate(
      "low mass",
      "return candidate.mass <= 2;"
    )

    val conjunction = circle && positiveMass
    val disjunction = circle || lowMass
    val negation = !positiveMass

    val conjunctionGenerator = generatorFor(stateType, conjunction, Vector.empty, Vector(3, 4, 1), 4)
    val disjunctionGenerator = generatorFor(stateType, disjunction, Vector.empty, Vector(3, 4, 1), 4)
    val negationGenerator = generatorFor(stateType, negation, Vector.empty, Vector(-1, 0, 3), 4)

    var index = 0
    while index < 8 do
      assert(conjunction(conjunctionGenerator.generate(s"and_$index")))
      assert(disjunction(disjunctionGenerator.generate(s"or_$index")))
      assert(negation(negationGenerator.generate(s"not_$index")))
      index += 1

    assert(conjunction.tree.program.statements.head.isInstanceOf[ReturnNode])
    assert(disjunction.tree.program.statements.head.isInstanceOf[ReturnNode])
    assert(negation.tree.program.statements.head.isInstanceOf[ReturnNode])

  test("embed parsed structural semantic and domain invariants with one automatic Space generator program"):
    val registry = new BaseTypes().registerAll()
    registerMetadataOperators(registry, Vector("Coordinates", "Momentum", "IntegratedState", "double", "int"))
    val stateType = new ValueType(
      "IntegratedState",
      LinkedHashMap[String, String | ValueType](
        "coordinates" -> new ValueType("Coordinates", Vector(2), Map("value" -> "double")),
        "momentum" -> new ValueType("Momentum", Vector(2), Map("value" -> "double")),
        "mass" -> "double",
        "particleCount" -> "int"
      )
    )
    stateType.attach_registry(registry)

    val structuralShape = Invariant(
      "matching coordinate arrays",
      new Predicate(
        "shape invariant program",
        "return state.coordinates.length() == 2 && state.coordinates.length() == state.momentum.length();",
        "state"
      )
    )
    val structuralMemory = Invariant(
      "contiguous memory",
      new Predicate(
        "memory invariant program",
        "return state.coordinates.offset() == 0 && state.momentum.offset() == state.coordinates.byte_size() && state.mass.offset() == state.momentum.offset() + state.momentum.byte_size();",
        "state"
      )
    )
    val semantic = Invariant(
      "circle and conservation",
      new Predicate(
        "semantic invariant program",
        "return state.coordinates[0] * state.coordinates[0] + state.coordinates[1] * state.coordinates[1] == 25 && state.mass == state.momentum[0] + state.momentum[1];",
        "state"
      )
    )
    val domains = Invariant(
      "primitive domains",
      new Predicate(
        "domain invariant program",
        "return state.mass >= 0 && state.mass <= 1000000 && state.particleCount >= 0 && state.particleCount <= 2147483647;",
        "state"
      )
    )

    val unified = structuralShape && structuralMemory && semantic && domains
    val automaticGenerator = generatorFor(
      stateType,
      unified.predicate,
      Vector(3.0, 4.0, 1.0, 2.0, 0.0),
      Vector(0, 2),
      8
    )

    val integratedSpace = new Space:
      def description = "integrated parsed-invariant physical state space"
      def value_type = stateType
      def structural_invariants = List(structuralShape, structuralMemory)
      def semantic_invariants = List(semantic, domains)
      val generator = automaticGenerator

    assert(integratedSpace.generator.program.statements.head.isInstanceOf[FunctionDeclarationNode])
    assert(integratedSpace.generator.models.nonEmpty)

    var generatedIndex = 0
    while generatedIndex < 24 do
      val generated = integratedSpace.generate()
      assert(integratedSpace.contains(generated))
      assert(integratedSpace.structural_invariants.forall(_.holds(generated)))
      assert(integratedSpace.semantic_invariants.forall(_.holds(generated)))
      generatedIndex += 1

    val invalid = integratedSpace.generate()
    invalid("mass") = -1.0
    assert(!integratedSpace.contains(invalid))
