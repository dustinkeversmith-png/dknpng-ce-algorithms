import value.*
import problem.space.*
import scala.collection.mutable.HashMap
import scala.collection.mutable.LinkedHashMap

class IntegralSpaceStressTests extends munit.FunSuite:

  test("validate complex runtime structural invariants beyond ValueType constructor requirements"):
    val registry = new BaseTypes().registerAll()

    def stateType(coordinateLength: Option[Int], momentumLength: Option[Int]): ValueType =
      val coordinates = new ValueType(
        "Coordinates",
        coordinateLength.map(Vector(_)).getOrElse(Vector.empty),
        Map("value" -> "double")
      )
      val momentum = new ValueType(
        "Momentum",
        momentumLength.map(Vector(_)).getOrElse(Vector.empty),
        Map("value" -> "double")
      )
      val result = new ValueType(
        "IntegralState",
        LinkedHashMap[String, String | ValueType](
          "coordinates" -> coordinates,
          "momentum" -> momentum,
          "mass" -> "double",
          "particleCount" -> "int"
        )
      )
      result.attach_registry(registry)
      result

    val designatedType = stateType(Some(3), Some(3))

    val nonEmptyVectors = Invariant(
      "coordinate and momentum vectors are non-empty and rank one",
      Predicate(
        "non_empty_rank_one",
        value =>
          val coordinates = value.reference_member("coordinates")
          val momentum = value.reference_member("momentum")
          coordinates.shape.length == 1 && momentum.shape.length == 1 &&
            coordinates.shape.head > 0 && momentum.shape.head > 0
      ),
      _ => "coordinates and momentum must be non-empty rank-one vectors"
    )

    val alignedDimensions = Invariant(
      "coordinate and momentum dimensions align",
      Predicate(
        "aligned_dimensions",
        value =>
          val coordinates = value.reference_member("coordinates")
          val momentum = value.reference_member("momentum")
          coordinates.shape == momentum.shape && coordinates.shape.headOption.exists(length => length >= 2 && length <= 4)
      ),
      _ => "coordinate and momentum dimensions did not align within [2,4]"
    )

    val contiguousAlignedMemory = Invariant(
      "field offsets, lengths, tails, and primitive alignment are coherent",
      Predicate(
        "contiguous_aligned_memory",
        value =>
          val coordinates = value.index("coordinates")
          val momentum = value.index("momentum")
          val mass = value.index("mass")
          val particleCount = value.index("particleCount")
          val coordinateView = value.reference_member("coordinates")
          val momentumView = value.reference_member("momentum")

          coordinates.offset == 0L &&
            momentum.offset == coordinates.offset + coordinates.length &&
            mass.offset == momentum.offset + momentum.length &&
            particleCount.offset == mass.offset + mass.length &&
            coordinates.length == coordinateView.shape.product.toLong * coordinateView.element_size &&
            momentum.length == momentumView.shape.product.toLong * momentumView.element_size &&
            coordinateView.tails == Vector(coordinateView.element_size) &&
            momentumView.tails == Vector(momentumView.element_size) &&
            coordinates.offset % 8L == 0L && momentum.offset % 8L == 0L &&
            mass.offset % 8L == 0L && particleCount.offset % 4L == 0L &&
            value.total_size == particleCount.offset + particleCount.length
      ),
      _ => "the recursive Value memory layout was not contiguous and aligned"
    )

    def structuralGenerator(): Value =
      val generated = new Value("structural_generated", designatedType)
      generated.attach_registry(registry)
      generated

    val structuralSpace = new Space:
      def description = "aligned dynamic-coordinate structural space"
      def value_type = designatedType
      def structural_invariants = List(nonEmptyVectors, alignedDimensions, contiguousAlignedMemory)
      def semantic_invariants = Nil
      def generate() = structuralGenerator()
      override def enumerate = LazyList(generate())

    val valid = structuralSpace.generate()
    val mismatched = new Value("mismatched", stateType(Some(2), Some(3)))
    mismatched.attach_registry(registry)
    val scalarCoordinates = new Value("scalarCoordinates", stateType(None, Some(3)))
    scalarCoordinates.attach_registry(registry)

    assert(structuralSpace.contains(valid))
    assert(nonEmptyVectors.holds(valid))
    assert(alignedDimensions.holds(valid))
    assert(contiguousAlignedMemory.holds(valid))
    assert(!structuralSpace.contains(mismatched))
    assert(!alignedDimensions.holds(mismatched))
    assert(!structuralSpace.contains(scalarCoordinates))
    assert(!nonEmptyVectors.holds(scalarCoordinates))

  test("validate complex FST semantic invariants and primitive domain bounds"):
    val registry = new BaseTypes().registerAll()
    val coordinatesType = new ValueType("Coordinates", Vector(3), Map("value" -> "double"))
    val momentumType = new ValueType("Momentum", Vector(3), Map("value" -> "double"))
    val stateType = new ValueType(
      "IntegralState",
      LinkedHashMap[String, String | ValueType](
        "coordinates" -> coordinatesType,
        "momentum" -> momentumType,
        "mass" -> "double",
        "particleCount" -> "int"
      )
    )
    stateType.attach_registry(registry)

    def compilePredicate(name: String, expression: String): Predicate =
      val syntax = parseExpression(expression) match
        case fastparse.Parsed.Success(parsed, _) => parsed
        case failure: fastparse.Parsed.Failure =>
          throw new AssertionError(failure.trace().longMsg)
      val tree = new FunctionalSemanticTree()
      tree.build(FunctionalTree(Vector(ReturnStatement(Some(syntax)))))
      Predicate(
        name,
        candidate =>
          val result = new Evaluator(
            tree,
            HashMap[String, Value]("state" -> candidate)
          ).evaluate().getOrElse(
            throw new AssertionError(s"Compiled predicate '$name' did not return a Value")
          )
          result.registry.caster.retrieve(result.base_type_name(), result) != 0.0
      )

    val circleInvariant = Invariant(
      "first two coordinates lie on radius five",
      compilePredicate(
        "circle_equation",
        "state.coordinates[0] * state.coordinates[0] + state.coordinates[1] * state.coordinates[1] == 25"
      ),
      _ => "x^2 + y^2 did not equal 25"
    )
    val massConservation = Invariant(
      "mass equals total momentum components",
      compilePredicate(
        "mass_conservation",
        "state.mass == state.momentum[0] + state.momentum[1] + state.momentum[2]"
      ),
      _ => "mass was not conserved"
    )
    val nonNegative = Invariant(
      "mass and particle count are non-negative",
      compilePredicate(
        "non_negative",
        "state.mass >= 0 && state.particleCount >= 0"
      ),
      _ => "mass or particle count was negative"
    )
    val primitiveDomains = Invariant(
      "primitive values remain finite and inside chosen domains",
      compilePredicate(
        "primitive_domains",
        "state.coordinates[0] >= -1000 && state.coordinates[0] <= 1000 && " +
          "state.coordinates[1] >= -1000 && state.coordinates[1] <= 1000 && " +
          "state.coordinates[2] >= -1000 && state.coordinates[2] <= 1000 && " +
          "state.mass >= -1000000 && state.mass <= 1000000 && " +
          "state.particleCount >= 0 && state.particleCount <= 2147483647"
      ),
      _ => "one or more primitive values exceeded the declared domain"
    )

    def generatedState(name: String): Value =
      val value = new Value(name, stateType)
      value.attach_registry(registry)
      value.reference_member("coordinates")(0) = 3.0
      value.reference_member("coordinates")(1) = 4.0
      value.reference_member("coordinates")(2) = 0.0
      value.reference_member("momentum")(0) = 1.0
      value.reference_member("momentum")(1) = 2.0
      value.reference_member("momentum")(2) = 3.0
      value("mass") = 6.0
      value("particleCount") = 10
      value

    val semanticSpace = new Space:
      def description = "FST-constrained physical state space"
      def value_type = stateType
      def structural_invariants = Nil
      def semantic_invariants = List(circleInvariant, massConservation, nonNegative, primitiveDomains)
      def generate() = generatedState("semantic_generated")
      override def enumerate = LazyList(generate())

    val valid = semanticSpace.generate()
    val offCircle = generatedState("offCircle")
    offCircle.reference_member("coordinates")(0) = 2.0
    val lostMass = generatedState("lostMass")
    lostMass("mass") = 7.0
    val negativeCount = generatedState("negativeCount")
    negativeCount("particleCount") = -1
    val outsideDomain = generatedState("outsideDomain")
    outsideDomain.reference_member("coordinates")(0) = 2000.0

    assert(semanticSpace.contains(valid))
    assert(!circleInvariant.holds(offCircle))
    assert(!massConservation.holds(lostMass))
    assert(!nonNegative.holds(negativeCount))
    assert(!primitiveDomains.holds(outsideDomain))

  test("compose predicates and invariants with conjunction disjunction and negation"):
    val registry = new BaseTypes().registerAll()
    val coordinatesType = new ValueType("Coordinates", Vector(2), Map("value" -> "double"))
    val stateType = new ValueType(
      "CompositionState",
      LinkedHashMap[String, String | ValueType](
        "coordinates" -> coordinatesType,
        "mass" -> "double",
        "particleCount" -> "int"
      )
    )
    stateType.attach_registry(registry)

    def state(name: String, x: Double, y: Double, mass: Double, count: Int): Value =
      val value = new Value(name, stateType)
      value.attach_registry(registry)
      value.reference_member("coordinates")(0) = x
      value.reference_member("coordinates")(1) = y
      value("mass") = mass
      value("particleCount") = count
      value

    def number(value: Value, member: String, typeName: String): Double =
      registry.caster.retrieve(typeName, value.reference_member(member))

    val circle = Predicate(
      "circle",
      value =>
        val coordinates = value.reference_member("coordinates")
        val x = registry.caster.retrieve("double", coordinates(0))
        val y = registry.caster.retrieve("double", coordinates(1))
        x * x + y * y == 25.0
    )
    val positiveMass = Predicate("positive_mass", value => number(value, "mass", "double") > 0.0)
    val lowCount = Predicate("low_count", value => number(value, "particleCount", "int") <= 2.0)

    val circleAndPositive = circle && positiveMass
    val circleOrLowCount = circle || lowCount
    val notPositiveMass = !positiveMass

    val circleInvariant = Invariant("circle", circle)
    val positiveInvariant = Invariant("positive mass", positiveMass)
    val lowCountInvariant = Invariant("low count", lowCount)
    val conjunction = circleInvariant && positiveInvariant
    val disjunction = circleInvariant || lowCountInvariant
    val negation = !positiveInvariant

    val circlePositive = state("circlePositive", 3.0, 4.0, 5.0, 10)
    val lowCountAlternative = state("lowCountAlternative", 1.0, 1.0, 5.0, 1)
    val negativeMassCircle = state("negativeMassCircle", 3.0, 4.0, -1.0, 10)
    val neither = state("neither", 1.0, 1.0, 5.0, 10)

    assert(circleAndPositive(circlePositive))
    assert(!circleAndPositive(negativeMassCircle))
    assert(circleOrLowCount(circlePositive))
    assert(circleOrLowCount(lowCountAlternative))
    assert(!circleOrLowCount(neither))
    assert(notPositiveMass(negativeMassCircle))
    assert(conjunction.holds(circlePositive))
    assert(!conjunction.holds(negativeMassCircle))
    assert(disjunction.holds(lowCountAlternative))
    assert(negation.holds(negativeMassCircle))

    def oneValueSpace(descriptionText: String, invariant: Invariant, generated: => Value, values: LazyList[Value]): Space =
      new Space:
        def description = descriptionText
        def value_type = stateType
        def structural_invariants = Nil
        def semantic_invariants = List(invariant)
        def generate() = generated
        override def enumerate = values

    val circleSpace = oneValueSpace(
      "circle space",
      circleInvariant,
      circlePositive,
      LazyList(circlePositive, negativeMassCircle)
    )
    val positiveSpace = oneValueSpace(
      "positive-mass space",
      positiveInvariant,
      circlePositive,
      LazyList(circlePositive, lowCountAlternative, neither)
    )
    val lowCountSpace = oneValueSpace(
      "low-count space",
      lowCountInvariant,
      lowCountAlternative,
      LazyList(lowCountAlternative)
    )

    val intersection = circleSpace.intersect(positiveSpace)
    val union = circleSpace.union(lowCountSpace)
    val difference = circleSpace.diff(positiveSpace)

    assert(intersection.contains(circlePositive))
    assert(!intersection.contains(negativeMassCircle))
    assert(union.contains(circlePositive))
    assert(union.contains(lowCountAlternative))
    assert(!union.contains(neither))
    assert(difference.contains(negativeMassCircle))
    assert(!difference.contains(circlePositive))
    assert(intersection.contains(intersection.generate()))
    assert(union.contains(union.generate()))
    assert(difference.contains(difference.generate()))

  test("embed structural semantic and domain invariants with an FST generator in one Space"):
    val registry = new BaseTypes().registerAll()
    val coordinatesType = new ValueType("Coordinates", Vector(3), Map("value" -> "double"))
    val momentumType = new ValueType("Momentum", Vector(3), Map("value" -> "double"))
    val stateType = new ValueType(
      "IntegratedPhysicalState",
      LinkedHashMap[String, String | ValueType](
        "coordinates" -> coordinatesType,
        "momentum" -> momentumType,
        "mass" -> "double",
        "particleCount" -> "int"
      )
    )
    stateType.attach_registry(registry)

    def compiledInvariant(name: String, expression: String, message: String): Invariant =
      val syntax = parseExpression(expression) match
        case fastparse.Parsed.Success(parsed, _) => parsed
        case failure: fastparse.Parsed.Failure =>
          throw new AssertionError(failure.trace().longMsg)
      val tree = new FunctionalSemanticTree()
      tree.build(FunctionalTree(Vector(ReturnStatement(Some(syntax)))))
      Invariant(
        name,
        Predicate(
          name,
          candidate =>
            val result = new Evaluator(
              tree,
              HashMap[String, Value]("state" -> candidate)
            ).evaluate().getOrElse(
              throw new AssertionError(s"Invariant '$name' did not return a Value")
            )
            result.registry.caster.retrieve(result.base_type_name(), result) != 0.0
        ),
        _ => message
      )

    val structuralShape = Invariant(
      "matching non-empty coordinate arrays",
      Predicate(
        "matching_shapes",
        value =>
          val coordinates = value.reference_member("coordinates")
          val momentum = value.reference_member("momentum")
          coordinates.shape == Vector(3) && momentum.shape == coordinates.shape &&
            coordinates.tails == Vector(8L) && momentum.tails == Vector(8L)
      )
    )
    val structuralMemory = Invariant(
      "aligned contiguous memory",
      Predicate(
        "aligned_memory",
        value =>
          val coordinates = value.index("coordinates")
          val momentum = value.index("momentum")
          val mass = value.index("mass")
          val count = value.index("particleCount")
          coordinates.offset == 0L && momentum.offset == 24L && mass.offset == 48L &&
            count.offset == 56L && value.total_size == 60L
      )
    )
    val circle = compiledInvariant(
      "circle",
      "state.coordinates[0] * state.coordinates[0] + state.coordinates[1] * state.coordinates[1] == 25",
      "coordinates did not satisfy the circle equation"
    )
    val conservation = compiledInvariant(
      "mass conservation",
      "state.mass == state.momentum[0] + state.momentum[1] + state.momentum[2]",
      "mass did not equal total momentum"
    )
    val domains = compiledInvariant(
      "primitive domains",
      "state.mass >= 0 && state.mass <= 1000000 && state.particleCount >= 0 && state.particleCount <= 2147483647",
      "primitive domain bounds were violated"
    )

    val generatorSource =
      """
        Value generate(Value result, int seed) {
          if (seed % 2 == 0) {
            result.coordinates[0] = 3;
            result.coordinates[1] = 4;
          } else {
            result.coordinates[0] = 4;
            result.coordinates[1] = 3;
          }
          result.coordinates[2] = 0;
          result.momentum[0] = seed % 5;
          result.momentum[1] = 2;
          result.momentum[2] = 3;
          result.mass = result.momentum[0] + result.momentum[1] + result.momentum[2];
          result.particleCount = seed % 100;
          return result;
        }

        return generate(result, seed);
        """.stripMargin
    val generatorSyntax = parseProgram(generatorSource) match
      case fastparse.Parsed.Success(tree, _) => tree
      case failure: fastparse.Parsed.Failure =>
        throw new AssertionError(failure.trace().longMsg)
    val generatorTree = new FunctionalSemanticTree()
    generatorTree.build(generatorSyntax)
    var nextSeed = 0

    def generateState(): Value =
      val storage = new Value(s"integrated_$nextSeed", stateType)
      storage.attach_registry(registry)
      val generated = new Evaluator(
        generatorTree,
        HashMap[String, Value](
          "result" -> storage,
          "seed" -> registry.caster.cast("int", nextSeed.toDouble)
        )
      ).evaluate().getOrElse(
        throw new AssertionError("The integrated generator did not return a Value")
      )
      nextSeed += 1
      generated

    val integratedSpace = new Space:
      def description = "integrated structural and semantic physical state space"
      def value_type = stateType
      def structural_invariants = List(structuralShape, structuralMemory)
      def semantic_invariants = List(circle, conservation, domains)
      def generate() = generateState()
      override def enumerate = LazyList.from(0).map(_ => generateState())

    var generatedIndex = 0
    while generatedIndex < 40 do
      val generated = integratedSpace.generate()
      assert(integratedSpace.contains(generated))
      assert(integratedSpace.structural_invariants.forall(_.holds(generated)))
      assert(integratedSpace.semantic_invariants.forall(_.holds(generated)))
      generatedIndex += 1

    val invalid = integratedSpace.generate()
    invalid("mass") = -1.0
    assert(!integratedSpace.contains(invalid))
    assert(!domains.holds(invalid))
