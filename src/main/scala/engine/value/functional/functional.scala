


trait class FunctionalCompute:

    // A little internal value creation state
    def vars: Map<String, Values>

    def run(
        program: FunctionalTree
        arguments: Map[String, Value]
    ):
        // The actual FunctionalTree will have like an array of in order AstTrees parsed from the parser
        // So we iterate through those, do the evaluation/traversal of the nodes, mutating the arguments internal values, which may more may not trigger a recursive call for a different functional associated with something else but ignore that
        // Nodes will have like variable names in them, so we can then use the args to associate those of course.
        // Also the internal vars like temp vars can be created on the vars tree in the program and reused of course.


trait class Functional:

  def compile()

  // Identification id of the functional itself.
  def id: FunctionalId

  // Parse/compile only once and cache the program.
  val program: FunctionalTree
  
  // Then what about mixed modal operators so maybe it would be
  // Map["Operator"]["ValueTypeA, ValueTypeB, ValueTypeC"]
  // Where the ValueType is the t value of Value
  // Then in execution, the arguments are already known to the value types, argument[]

  def execute(
    arguments: Map[String, Value]
  ): Value =

    val: FunctionalCompute

    // Execution algorithm
    // For each statement in the program the ast will compile and execute mutating the arguments state
    // But can also create and assign variables onto its own internal stack.






class FunctionalTests extends munit.FunSuite {
  test("test creating functionals") {
    // Maybe we can just use a premade language with tree sitter some C like programming language like glsl or something.
    val add =
    Functional(
        args = Vector("value", "other"),
        source =
        """
        result = value + other;
        other = result*2.0;
        """
    )
  }
}