// Bootstrap pack for the singular C-style computer types.
// A TypeRegistry starts empty; this pack explicitly adds sizes and lambda operators.
final class BaseTypes:

  var registry: TypeRegistry = new TypeRegistry()

  def registerAll(): TypeRegistry =


    this.registry.register_type("byte", 1)
    this.registry.register_type("short", 2)

    // Just go ahead and register a int here
    this.registry.register_type("int", 4)

    this.registry.register_type("long", 8)
    this.registry.register_type("float", 4)
    this.registry.register_type("double", 8)

    // Register this way for each of the operators on the base registry set.
    // this.registry.register_operator("int", ["add", ["a": "int", "b": "int"]], (a,b)=> a+b)

    val typeNames = this.registry.sizes.keys.toVector
    var leftTypeIndex = 0

    while leftTypeIndex < typeNames.length do
      val leftType = typeNames(leftTypeIndex)
      var rightTypeIndex = 0

      while rightTypeIndex < typeNames.length do
        val rightType = typeNames(rightTypeIndex)
        val argumentTypes = Map("a" -> leftType, "b" -> rightType)

        this.registry.register_operator(leftType, FunctionalId("add", argumentTypes), (a, arguments) => a.numeric_result(arguments(0), (left, right) => left + right))
        this.registry.register_operator(leftType, FunctionalId("+", argumentTypes), (a, arguments) => a.numeric_result(arguments(0), (left, right) => left + right))
        this.registry.register_operator(leftType, FunctionalId("subtract", argumentTypes), (a, arguments) => a.numeric_result(arguments(0), (left, right) => left - right))
        this.registry.register_operator(leftType, FunctionalId("-", argumentTypes), (a, arguments) => a.numeric_result(arguments(0), (left, right) => left - right))
        this.registry.register_operator(leftType, FunctionalId("multiply", argumentTypes), (a, arguments) => a.numeric_result(arguments(0), (left, right) => left * right))
        this.registry.register_operator(leftType, FunctionalId("*", argumentTypes), (a, arguments) => a.numeric_result(arguments(0), (left, right) => left * right))
        this.registry.register_operator(leftType, FunctionalId("divide", argumentTypes), (a, arguments) => a.numeric_result(arguments(0), (left, right) => left / right))
        this.registry.register_operator(leftType, FunctionalId("/", argumentTypes), (a, arguments) => a.numeric_result(arguments(0), (left, right) => left / right))
        this.registry.register_operator(leftType, FunctionalId("modulo", argumentTypes), (a, arguments) => a.numeric_result(arguments(0), (left, right) => left % right))
        this.registry.register_operator(leftType, FunctionalId("%", argumentTypes), (a, arguments) => a.numeric_result(arguments(0), (left, right) => left % right))

        this.registry.register_operator(leftType, FunctionalId("=", argumentTypes), (a, arguments) => a.assign(arguments(0)))
        this.registry.register_operator(leftType, FunctionalId("+=", argumentTypes), (a, arguments) => a.assign(a.numeric_result(arguments(0), (left, right) => left + right)))
        this.registry.register_operator(leftType, FunctionalId("-=", argumentTypes), (a, arguments) => a.assign(a.numeric_result(arguments(0), (left, right) => left - right)))
        this.registry.register_operator(leftType, FunctionalId("*=", argumentTypes), (a, arguments) => a.assign(a.numeric_result(arguments(0), (left, right) => left * right)))
        this.registry.register_operator(leftType, FunctionalId("/=", argumentTypes), (a, arguments) => a.assign(a.numeric_result(arguments(0), (left, right) => left / right)))
        this.registry.register_operator(leftType, FunctionalId("%=", argumentTypes), (a, arguments) => a.assign(a.numeric_result(arguments(0), (left, right) => left % right)))

        this.registry.register_operator(leftType, FunctionalId("<", argumentTypes), (a, arguments) => a.comparison_result(arguments(0), (left, right) => left < right))
        this.registry.register_operator(leftType, FunctionalId("<=", argumentTypes), (a, arguments) => a.comparison_result(arguments(0), (left, right) => left <= right))
        this.registry.register_operator(leftType, FunctionalId(">", argumentTypes), (a, arguments) => a.comparison_result(arguments(0), (left, right) => left > right))
        this.registry.register_operator(leftType, FunctionalId(">=", argumentTypes), (a, arguments) => a.comparison_result(arguments(0), (left, right) => left >= right))
        this.registry.register_operator(leftType, FunctionalId("==", argumentTypes), (a, arguments) => a.comparison_result(arguments(0), (left, right) => left == right))
        this.registry.register_operator(leftType, FunctionalId("!=", argumentTypes), (a, arguments) => a.comparison_result(arguments(0), (left, right) => left != right))
        this.registry.register_operator(leftType, FunctionalId("equals", argumentTypes), (a, arguments) => a.comparison_result(arguments(0), (left, right) => left == right))

        this.registry.register_operator(leftType, FunctionalId("&&", argumentTypes), (a, arguments) => a.boolean_result(arguments(0), (left, right) => left && right))
        this.registry.register_operator(leftType, FunctionalId("||", argumentTypes), (a, arguments) => a.boolean_result(arguments(0), (left, right) => left || right))

        rightTypeIndex += 1

      val unaryArgument = Map("a" -> leftType)
      this.registry.register_operator(leftType, FunctionalId("!", unaryArgument), (a, _) => a.boolean_result(a, (left, _) => !left))
      this.registry.register_operator(leftType, FunctionalId("unary+", unaryArgument), (a, _) => a.numeric_result(a, (left, _) => left))
      this.registry.register_operator(leftType, FunctionalId("unary-", unaryArgument), (a, _) => a.numeric_result(a, (left, _) => -left))

      leftTypeIndex += 1

    this.registry

    

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
