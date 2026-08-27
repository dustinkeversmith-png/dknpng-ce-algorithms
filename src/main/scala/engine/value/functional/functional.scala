\


trait Functional:
    Map[string, Value] stack
    Vector[string: args, operators: "", or like ast type thing but a program ast so it has access to stack or declaritive intermediary values that can be resampled]


final class Functional(
  val args: Vector[String],
  val source: String
):

  // Parse/compile only once.
  val program: FunctionalAst =
    FunctionalCompiler.compile(source)
  
  // Then what about mixed modal operators so maybe it would be
  // Map["Operator"]["ValueTypeA, ValueTypeB, ValueTypeC"]
  // Where the ValueType is the t value of Value
  // Then in execution, the arguments are already known to the value types, argument[]

  def execute(
    arguments: Map[String, Value]
  ): Value =
    program.execute(arguments)





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