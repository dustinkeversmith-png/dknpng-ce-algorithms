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

  test("synthesize a generator from a complex invariant FST and recursive Value schema"):
    case class PathStep(member: Option[String], indices: Vector[Int])
    case class SchemaSlot(
      path: Vector[PathStep],
      typeName: String,
      offset: Long,
      length: Long,
      enclosingShapes: Vector[Vector[Int]],
      enclosingTails: Vector[Vector[Long]]
    )
    case class Terminal(expression: Expr, value: Value)
    case class Model(terminals: Vector[Terminal])

    val registry = new BaseTypes().registerAll()
    val positionType = new ValueType(
      "Position",
      Vector(2),
      Map("value" -> "int")
    )
    val candidateType = new ValueType(
      "Candidate",
      Map("position" -> positionType, "mass" -> "double")
    )
    candidateType.attach_registry(registry)

    def elementCoordinates(shape: Vector[Int], linearIndex: Int): Vector[Int] =
      val coordinates = Array.ofDim[Int](shape.length)
      var remainder = linearIndex
      var dimensionIndex = shape.length - 1
      while dimensionIndex >= 0 do
        coordinates(dimensionIndex) = remainder % shape(dimensionIndex)
        remainder /= shape(dimensionIndex)
        dimensionIndex -= 1
      coordinates.toVector

    def deriveSchema(root: Value): Vector[SchemaSlot] =
      var slots: Vector[SchemaSlot] = Vector.empty

      def visit(
        current: Value,
        path: Vector[PathStep],
        enclosingShapes: Vector[Vector[Int]],
        enclosingTails: Vector[Vector[Long]]
      ): Unit =
        if current.shape.nonEmpty then
          var elementCount = 1
          var dimensionIndex = 0
          while dimensionIndex < current.shape.length do
            elementCount *= current.shape(dimensionIndex)
            dimensionIndex += 1

          var linearIndex = 0
          while linearIndex < elementCount do
            val coordinates = elementCoordinates(current.shape, linearIndex)
            visit(
              current.reference_element(coordinates.toArray),
              path :+ PathStep(None, coordinates),
              enclosingShapes :+ current.shape,
              enclosingTails :+ current.tails
            )
            linearIndex += 1
        else if current.registry.contains(current.t) then
          slots = slots :+ SchemaSlot(
            path,
            current.t,
            current.memory_offset,
            current.total_size,
            enclosingShapes,
            enclosingTails
          )
        else
          val fieldNames = current.fields.keys.toVector
          var fieldIndex = 0
          while fieldIndex < fieldNames.length do
            val fieldName = fieldNames(fieldIndex)
            visit(
              current.reference_member(fieldName),
              path :+ PathStep(Some(fieldName), Vector.empty),
              enclosingShapes,
              enclosingTails
            )
            fieldIndex += 1

      visit(root, Vector.empty, Vector.empty, Vector.empty)
      slots.sortBy(_.offset)

    def pathExpression(rootName: String, path: Vector[PathStep]): Expr =
      var expression: Expr = Variable(rootName)
      var stepIndex = 0
      while stepIndex < path.length do
        val step = path(stepIndex)
        step.member match
          case Some(memberName) => expression = Member(expression, memberName)
          case None =>
            val indices = step.indices.map(index => Literal(index.toDouble))
            if indices.length == 1 then expression = Index(expression, indices.head)
            else expression = MultiIndex(expression, indices)
        stepIndex += 1
      expression

    def resolve(root: Value, path: Vector[PathStep]): Value =
      var current = root
      var stepIndex = 0
      while stepIndex < path.length do
        val step = path(stepIndex)
        step.member match
          case Some(memberName) => current = current.reference_member(memberName)
          case None => current = current.reference_element(step.indices.toArray)
        stepIndex += 1
      current

    def compileProgram(source: String): FunctionalSemanticTree =
      val syntaxTree = parseProgram(source) match
        case fastparse.Parsed.Success(tree, _) => tree
        case failure: fastparse.Parsed.Failure =>
          throw new AssertionError(failure.trace().longMsg)
      val semanticTree = new FunctionalSemanticTree()
      semanticTree.build(syntaxTree)
      semanticTree

    def satisfies(invariantTree: FunctionalSemanticTree, candidate: Value): Boolean =
      val result = new Evaluator(
        invariantTree,
        HashMap[String, Value]("candidate" -> candidate)
      ).evaluate().getOrElse(
        throw new AssertionError("The compiled invariant did not return a Value")
      )
      result.registry.caster.retrieve(result.base_type_name(), result) != 0.0

    def terminal(typeName: String, number: Double): Terminal =
      Terminal(Literal(number), registry.caster.cast(typeName, number))

    val integerDomain = Vector(-5, 0, 3, 4, 2, 5, 7, -4, -3, -2, -1, 1, 6, 8, 9)
    val doubleDomain = Vector(-5, 5, 7) ++ (-10 to 18).filterNot(number => Set(-5, 5, 7).contains(number))
    val terminalGrammar = HashMap[String, Vector[Terminal]](
      "int" -> integerDomain.map(number => terminal("int", number.toDouble)),
      "double" -> doubleDomain.map(number => terminal("double", number.toDouble))
    )

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
    val invariantTree = compileProgram(invariantSource)

    def inverseModels(
      invariant: FunctionalSemanticTree,
      valueType: ValueType,
      slots: Vector[SchemaSlot],
      grammar: HashMap[String, Vector[Terminal]],
      maximumModels: Int
    ): Vector[Model] =
      val candidate = new Value("inverse_candidate", valueType)
      candidate.attach_registry(registry)
      var models: Vector[Model] = Vector.empty

      def solve(slotIndex: Int, chosen: Vector[Terminal]): Unit =
        if models.length < maximumModels then
          if slotIndex == slots.length then
            var assignmentIndex = 0
            while assignmentIndex < slots.length do
              resolve(candidate, slots(assignmentIndex).path)
                .operator("=")(chosen(assignmentIndex).value)
              assignmentIndex += 1
            if satisfies(invariant, candidate) then models = models :+ Model(chosen)
          else
            val terminals = grammar.getOrElse(
              slots(slotIndex).typeName,
              throw new NoSuchElementException(s"No terminal grammar exists for '${slots(slotIndex).typeName}'")
            )
            var terminalIndex = 0
            while terminalIndex < terminals.length && models.length < maximumModels do
              solve(slotIndex + 1, chosen :+ terminals(terminalIndex))
              terminalIndex += 1

      solve(0, Vector.empty)
      models

    def assignmentBlock(model: Model, slots: Vector[SchemaSlot]): Block =
      var assignments: Vector[Expr] = Vector.empty
      var slotIndex = 0
      while slotIndex < slots.length do
        assignments = assignments :+ Assign(
          pathExpression("result", slots(slotIndex).path),
          "=",
          model.terminals(slotIndex).expression
        )
        slotIndex += 1
      Block(assignments)

    def generatorStatements(models: Vector[Model], slots: Vector[SchemaSlot]): Vector[Expr] =
      require(models.nonEmpty, "Invariant inversion did not discover a satisfying model")
      if models.length == 1 then assignmentBlock(models.head, slots).statements
      else
        var fallback = assignmentBlock(models.last, slots)
        var modelIndex = models.length - 2
        while modelIndex >= 0 do
          val choicePredicate = BinaryOp(
            Variable("choice"),
            "==",
            Literal(modelIndex.toDouble)
          )
          fallback = Block(Vector(IfStatement(
            choicePredicate,
            assignmentBlock(models(modelIndex), slots),
            Some(fallback)
          )))
          modelIndex -= 1
        Vector(
          Declare(
            TypeName("int"),
            Variable("choice"),
            Some(BinaryOp(Variable("seed"), "%", Literal(models.length.toDouble)))
          ),
          fallback.statements.head
        )

    def synthesizeGenerator(
      invariant: FunctionalSemanticTree,
      valueType: ValueType,
      grammar: HashMap[String, Vector[Terminal]],
      maximumModels: Int
    ): (Vector[SchemaSlot], Vector[Model], FunctionalSemanticTree, Vector[Expr]) =
      val schemaValue = new Value("schema", valueType)
      schemaValue.attach_registry(registry)
      val slots = deriveSchema(schemaValue)
      val models = inverseModels(invariant, valueType, slots, grammar, maximumModels)
      val statements = generatorStatements(models, slots)
      val syntax = FunctionalTree(Vector(
        FunctionDeclaration(
          TypeName("Value"),
          Variable("generate"),
          Vector(
            Parameter(TypeName("Value"), Variable("result")),
            Parameter(TypeName("int"), Variable("seed"))
          ),
          Block(statements :+ ReturnStatement(Some(Variable("result"))))
        ),
        ReturnStatement(Some(Call(
          Variable("generate"),
          Vector(Variable("result"), Variable("seed"))
        )))
      ))
      val tree = new FunctionalSemanticTree()
      tree.build(syntax)
      (slots, models, tree, statements)

    val (schema, models, generatorTree, synthesizedStatements) = synthesizeGenerator(
      invariantTree,
      candidateType,
      terminalGrammar,
      maximumModels = 4
    )

    assert(schema.map(_.typeName) == Vector("int", "int", "double"))
    assert(schema.map(_.offset) == Vector(0L, 4L, 8L))
    assert(schema.map(_.length) == Vector(4L, 4L, 8L))
    assert(schema.head.enclosingShapes == Vector(Vector(2)))
    assert(schema.head.enclosingTails == Vector(Vector(4L)))
    assert(models.nonEmpty)

    assert(generatorTree.program.statements.head.isInstanceOf[FunctionDeclarationNode])
    assert(synthesizedStatements.exists(_.isInstanceOf[IfStatement]))

    var seed = 0
    while seed < models.length * 3 do
      val generatedStorage = new Value(s"generated_$seed", candidateType)
      generatedStorage.attach_registry(registry)
      val generated = new Evaluator(
        generatorTree,
        HashMap[String, Value](
          "result" -> generatedStorage,
          "seed" -> registry.caster.cast("int", seed.toDouble)
        )
      ).evaluate().getOrElse(
        throw new AssertionError("The synthesized generator did not return a Value")
      )

      assert(satisfies(invariantTree, generated))
      seed += 1

  test("compare rejection sampling with an automatically assembled complex-predicate generator"):
    case class Terminal(expression: Expr, value: Value)
    case class Model(terminals: Vector[Terminal])

    val registry = new BaseTypes().registerAll()
    val vectorType = new ValueType(
      "ComplexVector",
      Vector(2),
      Map("value" -> "int")
    )
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
    val syntaxTree = parseProgram(invariantSource) match
      case fastparse.Parsed.Success(tree, _) => tree
      case failure: fastparse.Parsed.Failure =>
        throw new AssertionError(failure.trace().longMsg)
    val invariantTree = new FunctionalSemanticTree()
    invariantTree.build(syntaxTree)

    def isValid(candidate: Value): Boolean =
      val result = new Evaluator(
        invariantTree,
        HashMap[String, Value]("candidate" -> candidate)
      ).evaluate().getOrElse(
        throw new AssertionError("The complex sampling invariant did not return a Value")
      )
      result.registry.caster.retrieve(result.base_type_name(), result) != 0.0

    var rejectionAttempts = 0
    var rejectionAccepted = 0
    var rejectionRejected = 0
    while rejectionAccepted < 5 && rejectionAttempts < 500 do
      val candidate = new Value(s"sample_$rejectionAttempts", vectorType)
      candidate.attach_registry(registry)
      candidate(0) = (rejectionAttempts % 15) - 5
      candidate(1) = ((rejectionAttempts * 3) % 15) - 5
      rejectionAttempts += 1

      if isValid(candidate) then rejectionAccepted += 1
      else rejectionRejected += 1

    assert(rejectionAccepted == 5)

    val terminals = (-5 to 9).toVector.map(number =>
      Terminal(Literal(number.toDouble), registry.caster.cast("int", number.toDouble))
    )
    var satisfyingModels: Vector[Model] = Vector.empty
    var leftIndex = 0
    while leftIndex < terminals.length do
      var rightIndex = 0
      while rightIndex < terminals.length do
        val candidate = new Value("solver_candidate", vectorType)
        candidate.attach_registry(registry)
        candidate(0) = terminals(leftIndex).value
        candidate(1) = terminals(rightIndex).value
        if isValid(candidate) then
          satisfyingModels = satisfyingModels :+ Model(Vector(terminals(leftIndex), terminals(rightIndex)))
        rightIndex += 1
      leftIndex += 1

    assert(satisfyingModels.nonEmpty)

    def modelAssignments(model: Model): Block =
      Block(Vector(
        Assign(Index(Variable("result"), Literal(0.0)), "=", model.terminals(0).expression),
        Assign(Index(Variable("result"), Literal(1.0)), "=", model.terminals(1).expression)
      ))

    var fallback = modelAssignments(satisfyingModels.last)
    var modelIndex = satisfyingModels.length - 2
    while modelIndex >= 0 do
      fallback = Block(Vector(IfStatement(
        BinaryOp(Variable("choice"), "==", Literal(modelIndex.toDouble)),
        modelAssignments(satisfyingModels(modelIndex)),
        Some(fallback)
      )))
      modelIndex -= 1

    val automaticallyAssembledBody = Vector[Expr](
      Declare(
        TypeName("int"),
        Variable("choice"),
        Some(BinaryOp(Variable("seed"), "%", Literal(satisfyingModels.length.toDouble)))
      ),
      fallback.statements.head,
      ReturnStatement(Some(Variable("result")))
    )
    val generatorTree = new FunctionalSemanticTree()
    generatorTree.build(FunctionalTree(Vector(
      FunctionDeclaration(
        TypeName("Value"),
        Variable("generate"),
        Vector(
          Parameter(TypeName("Value"), Variable("result")),
          Parameter(TypeName("int"), Variable("seed"))
        ),
        Block(automaticallyAssembledBody)
      ),
      ReturnStatement(Some(Call(
        Variable("generate"),
        Vector(Variable("result"), Variable("seed"))
      )))
    )))

    var constructiveAttempts = 0
    var constructiveAccepted = 0
    var constructiveRejected = 0
    while constructiveAttempts < 20 do
      val generatedStorage = new Value(s"constructive_$constructiveAttempts", vectorType)
      generatedStorage.attach_registry(registry)
      val generated = new Evaluator(
        generatorTree,
        HashMap[String, Value](
          "result" -> generatedStorage,
          "seed" -> registry.caster.cast("int", constructiveAttempts.toDouble)
        )
      ).evaluate().getOrElse(
        throw new AssertionError("The automatically assembled generator did not return a Value")
      )
      constructiveAttempts += 1

      if isValid(generated) then constructiveAccepted += 1
      else constructiveRejected += 1

    assert(rejectionAttempts > rejectionAccepted)
    assert(rejectionRejected > 0)
    assert(constructiveAttempts == constructiveAccepted)
    assert(constructiveRejected == 0)
