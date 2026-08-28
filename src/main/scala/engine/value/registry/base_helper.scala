// Bootstrap pack for the singular C-style computer types.
// A TypeRegistry starts empty; this pack explicitly adds sizes and lambda operators.
final class BaseTypes(var registry: TypeRegistry) = TypeRegistry:

  def registerAll():


    // Just go ahead and register a int here
    this.registry.register_type("int", 4)

    // Register this way for each of the operators on the base registry set.
    this.registry.register_operator("int", ["add", ["a": "int", "b": "int"]], (a,b)=> a+b)

    

    //basetypes.register_operator("double", ["x","a", "b"], "x = a + b;")
    //registry.

    // operators.register("=", (left, arguments) => left.assign(arguments(0)))
    // operators.register("+", (left, arguments) => left.numeric_result(arguments(0), (a, b) => a + b))
    // operators.register("-", (left, arguments) => left.numeric_result(arguments(0), (a, b) => a - b))
    // operators.register("*", (left, arguments) => left.numeric_result(arguments(0), (a, b) => a * b))
    // operators.register("/", (left, arguments) => left.numeric_result(arguments(0), (a, b) => a / b))
    // operators.register("%", (left, arguments) => left.numeric_result(arguments(0), (a, b) => a % b))

    // operators.register("+=", (left, arguments) => left.assign(left.operators("+")(arguments(0))))
    // operators.register("-=", (left, arguments) => left.assign(left.operators("-")(arguments(0))))
    // operators.register("*=", (left, arguments) => left.assign(left.operators("*")(arguments(0))))
    // operators.register("/=", (left, arguments) => left.assign(left.operators("/")(arguments(0))))
    // operators.register("%=", (left, arguments) => left.assign(left.operators("%")(arguments(0))))

    // operators.register("<", (left, arguments) => left.comparison_result(arguments(0), (a, b) => a < b))
    // operators.register("<=", (left, arguments) => left.comparison_result(arguments(0), (a, b) => a <= b))
    // operators.register(">", (left, arguments) => left.comparison_result(arguments(0), (a, b) => a > b))
    // operators.register(">=", (left, arguments) => left.comparison_result(arguments(0), (a, b) => a >= b))
    // operators.register("==", (left, arguments) => left.comparison_result(arguments(0), (a, b) => a == b))
    // operators.register("!=", (left, arguments) => left.comparison_result(arguments(0), (a, b) => a != b))
    // operators.register("equals", (left, arguments) => left.comparison_result(arguments(0), (a, b) => a == b))

    // operators.register("&&", (left, arguments) => left.boolean_result(arguments(0), (a, b) => a && b))
    // operators.register("||", (left, arguments) => left.boolean_result(arguments(0), (a, b) => a || b))
    // operators.register("!", (left, _) => left.boolean_result(left, (a, _) => !a))
    // operators.register("unary+", (left, _) => left.numeric_result(this.registry.literal(0.0), (a, _) => a))
    // operators.register("unary-", (left, _) => left.numeric_result(this.registry.literal(-1.0), (a, b) => a * b))
