// using a valuetype description and invariants create a constructive generator function or constructive grammar from the invaraitns
// This is the execution space of course


// Derive the generator grammar directly from the structural schema of the ValueType (e.g., field types, shapes, tails, and offsets).

// synthesis via constraint inversion (correct-by-construction generation)

// in the first test, testing building an inversion scheme to create the program automatically just from the invariant/s

// in the second test show examples of sampling and rejection and the inversion techniques

// Why Inverting the AST is NecessaryConstructive Target: Invariants are validation functions ($\mathcal{I}: \text{Value} \to \text{Boolean}$). Generators are synthesis functions ($\mathcal{G}: \text{Seed}/\text{Param} \to \text{Value}$).  The Inversion Step: You cannot just run an invariant FST to get data; you must transform the invariant AST into generator assignments before compiling it into the execution tree.  

import value.*
import problem.space.*
import scala.collection.mutable.HashMap

class ConstructiveGeneratorTests extends munit.FunSuite:

  test("invert compiled invariant AST bounds into a schema-derived generator AST"):
    case class SchemaSlot(
      elementIndex: Int,
      typeName: String,
      offset: Long,
      length: Long,
      tail: Long
    )
    case class Bounds(var minimum: Int, var maximum: Int)

    val registry = new BaseTypes().registerAll()
    val vectorType = new ValueType(
      "BoundedVector",
      Vector(4),
      Map("value" -> "int")
    )
    vectorType.attach_registry(registry)
    val schemaValue = new Value("schema", vectorType)
    schemaValue.attach_registry(registry)

    var schemaSlots: Vector[SchemaSlot] = Vector.empty
    var elementIndex = 0
    while elementIndex < vectorType.shape.head do
      val element = schemaValue.index_dimension(elementIndex)
      val field = schemaValue.index("value")
      schemaSlots = schemaSlots :+ SchemaSlot(
        elementIndex,
        field.valueType.t,
        element.offset + field.offset,
        field.length,
        schemaValue.tail(0)
      )
      elementIndex += 1

    assert(schemaSlots.map(_.typeName) == Vector.fill(4)("int"))
    assert(schemaSlots.map(_.offset) == Vector(0L, 4L, 8L, 12L))
    assert(schemaSlots.map(_.length) == Vector.fill(4)(4L))
    assert(schemaSlots.map(_.tail) == Vector.fill(4)(4L))

    def conjunction(expressions: Vector[Expr]): Expr =
      require(expressions.nonEmpty, "A compiled invariant requires at least one predicate AST")
      var combined = expressions.head
      var expressionIndex = 1
      while expressionIndex < expressions.length do
        combined = BinaryOp(combined, "&&", expressions(expressionIndex))
        expressionIndex += 1
      combined

    var lowerPredicates: Vector[Expr] = Vector.empty
    var upperPredicates: Vector[Expr] = Vector.empty
    elementIndex = 0
    while elementIndex < schemaSlots.length do
      val candidateElement = Index(Variable("candidate"), Literal(elementIndex.toDouble))
      lowerPredicates = lowerPredicates :+ BinaryOp(candidateElement, ">=", Literal(0.0))
      upperPredicates = upperPredicates :+ BinaryOp(candidateElement, "<=", Literal(9.0))
      elementIndex += 1

    val lowerInvariantAst = conjunction(lowerPredicates)
    val upperInvariantAst = conjunction(upperPredicates)
    val unifiedInvariantAst = BinaryOp(lowerInvariantAst, "&&", upperInvariantAst)

    val invariantTree = new FunctionalSemanticTree()
    invariantTree.build(FunctionalTree(Vector(ReturnStatement(Some(unifiedInvariantAst)))))
    val compiledInvariant = Invariant(
      "every generated element is between zero and nine",
      Predicate(
        "P1 && P2",
        (candidate: Value) =>
          val result = new Evaluator(
            invariantTree,
            HashMap[String, Value]("candidate" -> candidate)
          ).evaluate().getOrElse(
            throw new AssertionError("The compiled invariant did not return a Value")
          )
          result.registry.caster.retrieve(result.base_type_name(), result) != 0.0
      )
    )

    val invertedBounds = HashMap.empty[Int, Bounds]
    elementIndex = 0
    while elementIndex < schemaSlots.length do
      invertedBounds(elementIndex) = Bounds(Int.MinValue, Int.MaxValue)
      elementIndex += 1

    def invertInvariant(node: Expr): Unit =
      node match
        case BinaryOp(left, "&&", right) =>
          invertInvariant(left)
          invertInvariant(right)
        case BinaryOp(Index(Variable("candidate"), Literal(index)), ">=", Literal(bound)) =>
          invertedBounds(index.toInt).minimum = bound.toInt
        case BinaryOp(Index(Variable("candidate"), Literal(index)), "<=", Literal(bound)) =>
          invertedBounds(index.toInt).maximum = bound.toInt
        case unsupported =>
          throw new IllegalArgumentException(s"The generator cannot invert invariant AST node: $unsupported")

    invertInvariant(unifiedInvariantAst)

    var generatorAssignments: Vector[Expr] = Vector.empty
    elementIndex = 0
    while elementIndex < schemaSlots.length do
      val slot = schemaSlots(elementIndex)
      val bounds = invertedBounds(slot.elementIndex)
      require(bounds.minimum != Int.MinValue && bounds.maximum != Int.MaxValue)
      val width = bounds.maximum - bounds.minimum + 1
      val seedForSlot = BinaryOp(
        BinaryOp(Variable("seed"), "+", Literal(slot.elementIndex.toDouble)),
        "%",
        Literal(width.toDouble)
      )
      val generatedValue = BinaryOp(Literal(bounds.minimum.toDouble), "+", seedForSlot)
      generatorAssignments = generatorAssignments :+ Assign(
        Index(Variable("result"), Literal(slot.elementIndex.toDouble)),
        "=",
        generatedValue
      )
      elementIndex += 1

    val generatorFunction = FunctionDeclaration(
      TypeName("Value"),
      Variable("generate"),
      Vector(
        Parameter(TypeName("Value"), Variable("result")),
        Parameter(TypeName("int"), Variable("seed"))
      ),
      Block(generatorAssignments :+ ReturnStatement(Some(Variable("result"))))
    )
    val generatorSyntax = FunctionalTree(Vector(
      generatorFunction,
      ReturnStatement(Some(Call(
        Variable("generate"),
        Vector(Variable("result"), Variable("seed"))
      )))
    ))
    val generatorTree = new FunctionalSemanticTree()
    val generatorProgram = generatorTree.build(generatorSyntax)

    assert(generatorProgram.statements.head.isInstanceOf[FunctionDeclarationNode])
    assert(generatorAssignments.length == schemaSlots.length)

    var seed = 0
    while seed < 25 do
      val generatedStorage = new Value(s"generated_$seed", vectorType)
      generatedStorage.attach_registry(registry)
      val seedValue = registry.caster.cast("int", seed.toDouble)
      val generated = new Evaluator(
        generatorTree,
        HashMap[String, Value]("result" -> generatedStorage, "seed" -> seedValue)
      ).evaluate().getOrElse(
        throw new AssertionError("The inverted generator did not return a Value")
      )

      assert(compiledInvariant.holds(generated))
      seed += 1

  test("contrast rejection sampling with constructive invariant inversion"):
    val registry = new BaseTypes().registerAll()
    val vectorType = new ValueType(
      "SampleVector",
      Vector(2),
      Map("value" -> "int")
    )
    vectorType.attach_registry(registry)

    val lower0 = BinaryOp(Index(Variable("candidate"), Literal(0.0)), ">=", Literal(0.0))
    val upper0 = BinaryOp(Index(Variable("candidate"), Literal(0.0)), "<=", Literal(9.0))
    val lower1 = BinaryOp(Index(Variable("candidate"), Literal(1.0)), ">=", Literal(0.0))
    val upper1 = BinaryOp(Index(Variable("candidate"), Literal(1.0)), "<=", Literal(9.0))
    val unifiedInvariantAst = BinaryOp(
      BinaryOp(lower0, "&&", upper0),
      "&&",
      BinaryOp(lower1, "&&", upper1)
    )

    val invariantTree = new FunctionalSemanticTree()
    invariantTree.build(FunctionalTree(Vector(ReturnStatement(Some(unifiedInvariantAst)))))

    def isValid(candidate: Value): Boolean =
      val result = new Evaluator(
        invariantTree,
        HashMap[String, Value]("candidate" -> candidate)
      ).evaluate().getOrElse(
        throw new AssertionError("The sampling invariant did not return a Value")
      )
      result.registry.caster.retrieve(result.base_type_name(), result) != 0.0

    var rejectionAttempts = 0
    var rejectionAccepted = 0
    var rejectionRejected = 0
    while rejectionAccepted < 5 do
      val candidate = new Value(s"sample_$rejectionAttempts", vectorType)
      candidate.attach_registry(registry)
      candidate(0) = rejectionAttempts - 12
      candidate(1) = rejectionAttempts - 11
      rejectionAttempts += 1

      if isValid(candidate) then rejectionAccepted += 1
      else rejectionRejected += 1

    val invertedAssignments = Vector[Expr](
      Assign(
        Index(Variable("result"), Literal(0.0)),
        "=",
        BinaryOp(Variable("seed"), "%", Literal(10.0))
      ),
      Assign(
        Index(Variable("result"), Literal(1.0)),
        "=",
        BinaryOp(BinaryOp(Variable("seed"), "+", Literal(1.0)), "%", Literal(10.0))
      )
    )
    val invertedGeneratorTree = new FunctionalSemanticTree()
    invertedGeneratorTree.build(FunctionalTree(Vector(
      FunctionDeclaration(
        TypeName("Value"),
        Variable("generate"),
        Vector(
          Parameter(TypeName("Value"), Variable("result")),
          Parameter(TypeName("int"), Variable("seed"))
        ),
        Block(invertedAssignments :+ ReturnStatement(Some(Variable("result"))))
      ),
      ReturnStatement(Some(Call(
        Variable("generate"),
        Vector(Variable("result"), Variable("seed"))
      )))
    )))

    var constructiveAttempts = 0
    var constructiveAccepted = 0
    var constructiveRejected = 0
    while constructiveAttempts < 5 do
      val generatedStorage = new Value(s"constructive_$constructiveAttempts", vectorType)
      generatedStorage.attach_registry(registry)
      val seedValue = registry.caster.cast("int", constructiveAttempts.toDouble)
      val generated = new Evaluator(
        invertedGeneratorTree,
        HashMap[String, Value]("result" -> generatedStorage, "seed" -> seedValue)
      ).evaluate().getOrElse(
        throw new AssertionError("The constructive generator did not return a Value")
      )
      constructiveAttempts += 1

      if isValid(generated) then constructiveAccepted += 1
      else constructiveRejected += 1

    assert(rejectionAttempts > rejectionAccepted)
    assert(rejectionRejected > 0)
    assert(constructiveAttempts == constructiveAccepted)
    assert(constructiveRejected == 0)
