package value

import fastparse.Parsed



trait FunctionalCompute:

    // A little internal value creation state
    def vars: Map[String, Value]

    def run(
        program: FunctionalTree,
        arguments: Map[String, Value]
    ): Unit
        // The actual FunctionalTree will have like an array of in order AstTrees parsed from the parser
        // So we iterate through those, do the evaluation/traversal of the nodes, mutating the arguments internal values, which may more may not trigger a recursive call for a different functional associated with something else but ignore that
        // Nodes will have like variable names in them, so we can then use the args to associate those of course.
        // Also the internal vars like temp vars can be created on the vars tree in the program and reused of course.


final class Functional(
  val args: Vector[String],
  val source: String
):

  def compile(): FunctionalTree = FunctionalCompiler.compile(source)

  // Identification id of the functional itself.
  def id: FunctionalId = FunctionalId(source, args.map(_ -> "Value").toMap)

  // Parse/compile only once and cache the program.
  lazy val program: FunctionalTree = compile()

  // Then what about mixed modal operators so maybe it would be
  // Map["Operator"]["ValueTypeA, ValueTypeB, ValueTypeC"]
  // Where the ValueType is the t value of Value
  // Then in execution, the arguments are already known to the value types, argument[]

  def execute(
    arguments: Map[String, Value]
  ): Value =
    throw new UnsupportedOperationException("Functional AST evaluation is not built yet")

    // Execution algorithm
    // For each statement in the program the ast will compile and execute mutating the arguments state
    // But can also create and assign variables onto its own internal stack.


object Functional:
  def apply(args: Vector[String], source: String): Functional =
    new Functional(args, source)


object FunctionalCompiler:
  def compile(source: String): FunctionalTree =
    parseProgram(source) match
      case Parsed.Success(tree, _) => tree
      case failure: Parsed.Failure =>
        throw new IllegalArgumentException(failure.trace().longMsg)






// Maybe we can just use a premade language with tree sitter some C like programming language like glsl or something.
