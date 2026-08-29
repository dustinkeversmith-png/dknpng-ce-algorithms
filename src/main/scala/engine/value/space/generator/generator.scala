package problem.space

import scala.collection.mutable.HashMap
import value.*

/** One step through the recursive ValueType schema. */
final class GeneratorPathStep(var member: Option[String], var indices: Vector[Int])

/** A primitive leaf derived from the ValueType shape, fields, tails, and offsets. */
final class GeneratorSchemaSlot(
  var path: Vector[GeneratorPathStep],
  var type_name: String,
  var offset: Long,
  var length: Long,
  var enclosing_shapes: Vector[Vector[Int]],
  var enclosing_tails: Vector[Vector[Long]]
)

/** A typed terminal which can be written into a synthesized generator program. */
final class GeneratorTerminal(var expression: Expr, var value: Value)

/** One satisfying assignment discovered by inverting the invariant program. */
final class GeneratorModel(var terminals: Vector[GeneratorTerminal])

/**
 * Constructs a generator program from a ValueType and a compiled invariant.
 *
 * The terminal grammar describes the finite primitive domain available to the
 * solver. The assignment statements and control flow are derived automatically;
 * callers never provide a handwritten generation function.
 */
final class Generator(
  var value_type: ValueType,
  var invariant: Predicate,
  var maximum_models: Int
):
  var terminal_grammar: HashMap[String, Vector[GeneratorTerminal]] = HashMap.empty
  var schema: Vector[GeneratorSchemaSlot] = Vector.empty
  var models: Vector[GeneratorModel] = Vector.empty
  var syntax: FunctionalTree = FunctionalTree(Vector.empty)
  var tree: FunctionalSemanticTree = new FunctionalSemanticTree()
  var program: ProgramNode = ProgramNode(Vector.empty)
  var next_seed: Int = 0

  def this(value_type: ValueType, invariant: Predicate) =
    this(value_type, invariant, 32)

  def register_terminal(type_name: String, expression: Expr, value: Value): Unit =
    val terminals = this.terminal_grammar.getOrElse(type_name, Vector.empty)
    this.terminal_grammar(type_name) = terminals :+ new GeneratorTerminal(expression, value)

  def register_number(type_name: String, number: Double): Unit =
    this.register_terminal(
      type_name,
      Literal(number),
      this.value_type.registry.caster.cast(type_name, number)
    )

  def element_coordinates(shape: Vector[Int], linear_index: Int): Vector[Int] =
    val coordinates = Array.ofDim[Int](shape.length)
    var remainder = linear_index
    var dimension_index = shape.length - 1
    while dimension_index >= 0 do
      coordinates(dimension_index) = remainder % shape(dimension_index)
      remainder /= shape(dimension_index)
      dimension_index -= 1
    coordinates.toVector

  def derive_schema(): Vector[GeneratorSchemaSlot] =
    val root = new Value("generator_schema", this.value_type)
    root.attach_registry(this.value_type.registry)
    var slots: Vector[GeneratorSchemaSlot] = Vector.empty

    def visit(
      current: Value,
      path: Vector[GeneratorPathStep],
      enclosing_shapes: Vector[Vector[Int]],
      enclosing_tails: Vector[Vector[Long]]
    ): Unit =
      if current.shape.nonEmpty then
        val element_count = current.shape.product
        var linear_index = 0
        while linear_index < element_count do
          val coordinates = this.element_coordinates(current.shape, linear_index)
          visit(
            current.reference_element(coordinates.toArray),
            path :+ new GeneratorPathStep(None, coordinates),
            enclosing_shapes :+ current.shape,
            enclosing_tails :+ current.tails
          )
          linear_index += 1
      else if current.registry.contains(current.t) then
        slots = slots :+ new GeneratorSchemaSlot(
          path,
          current.t,
          current.memory_offset,
          current.total_size,
          enclosing_shapes,
          enclosing_tails
        )
      else
        val field_names = current.fields.keys.toVector
        var field_index = 0
        while field_index < field_names.length do
          val field_name = field_names(field_index)
          visit(
            current.reference_member(field_name),
            path :+ new GeneratorPathStep(Some(field_name), Vector.empty),
            enclosing_shapes,
            enclosing_tails
          )
          field_index += 1

    visit(root, Vector.empty, Vector.empty, Vector.empty)
    this.schema = slots.sortBy(_.offset)
    this.schema

  def path_expression(root_name: String, path: Vector[GeneratorPathStep]): Expr =
    var expression: Expr = Variable(root_name)
    var step_index = 0
    while step_index < path.length do
      val step = path(step_index)
      step.member match
        case Some(member_name) => expression = Member(expression, member_name)
        case None =>
          val indices = step.indices.map(index => Literal(index.toDouble))
          if indices.length == 1 then expression = Index(expression, indices.head)
          else expression = MultiIndex(expression, indices)
      step_index += 1
    expression

  def resolve(root: Value, path: Vector[GeneratorPathStep]): Value =
    var current = root
    var step_index = 0
    while step_index < path.length do
      val step = path(step_index)
      step.member match
        case Some(member_name) => current = current.reference_member(member_name)
        case None => current = current.reference_element(step.indices.toArray)
      step_index += 1
    current

  def inverse_models(): Vector[GeneratorModel] =
    require(this.maximum_models > 0, "A generator must retain at least one satisfying model")
    if this.schema.isEmpty then this.derive_schema()

    val candidate = new Value("inverse_candidate", this.value_type)
    candidate.attach_registry(this.value_type.registry)
    var discovered: Vector[GeneratorModel] = Vector.empty

    def solve(slot_index: Int, chosen: Vector[GeneratorTerminal]): Unit =
      if discovered.length < this.maximum_models then
        if slot_index == this.schema.length then
          var assignment_index = 0
          while assignment_index < this.schema.length do
            this.resolve(candidate, this.schema(assignment_index).path)
              .operator("=")(chosen(assignment_index).value)
            assignment_index += 1
          if this.invariant(candidate) then discovered = discovered :+ new GeneratorModel(chosen)
        else
          val slot = this.schema(slot_index)
          val terminals = this.terminal_grammar.getOrElse(
            slot.type_name,
            throw new NoSuchElementException(
              s"No terminal grammar exists for '${slot.type_name}' at byte offset ${slot.offset}"
            )
          )
          var terminal_index = 0
          while terminal_index < terminals.length && discovered.length < this.maximum_models do
            solve(slot_index + 1, chosen :+ terminals(terminal_index))
            terminal_index += 1

    solve(0, Vector.empty)
    this.models = discovered
    this.models

  def assignment_block(model: GeneratorModel): Block =
    var assignments: Vector[Expr] = Vector.empty
    var slot_index = 0
    while slot_index < this.schema.length do
      assignments = assignments :+ Assign(
        this.path_expression("result", this.schema(slot_index).path),
        "=",
        model.terminals(slot_index).expression
      )
      slot_index += 1
    Block(assignments)

  def generator_statements(): Vector[Expr] =
    require(this.models.nonEmpty, "Invariant inversion did not discover a satisfying model")
    if this.models.length == 1 then this.assignment_block(this.models.head).statements
    else
      var fallback = this.assignment_block(this.models.last)
      var model_index = this.models.length - 2
      while model_index >= 0 do
        fallback = Block(Vector(IfStatement(
          BinaryOp(Variable("choice"), "==", Literal(model_index.toDouble)),
          this.assignment_block(this.models(model_index)),
          Some(fallback)
        )))
        model_index -= 1
      Vector(
        Declare(
          TypeName("int"),
          Variable("choice"),
          Some(BinaryOp(Variable("seed"), "%", Literal(this.models.length.toDouble)))
        ),
        fallback.statements.head
      )

  def synthesize(): ProgramNode =
    this.derive_schema()
    this.inverse_models()
    val statements = this.generator_statements()
    this.syntax = FunctionalTree(Vector(
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
    this.tree = new FunctionalSemanticTree()
    this.program = this.tree.build(this.syntax)
    this.program

  def generate(name: String): Value =
    require(this.program.statements.nonEmpty, "synthesize() must build the generator program before generation")
    val storage = new Value(name, this.value_type)
    storage.attach_registry(this.value_type.registry)
    val generated = new Evaluator(
      this.tree,
      HashMap[String, Value](
        "result" -> storage,
        "seed" -> this.value_type.registry.caster.cast("int", this.next_seed.toDouble)
      )
    ).evaluate().getOrElse(
      throw new IllegalStateException("The synthesized generator program did not return a Value")
    )
    this.next_seed += 1
    generated
