// Bootstrap pack for the singular C-style computer types.
// A TypeRegistry starts empty; this pack explicitly adds sizes and lambda operators.
import java.nio.ByteBuffer

final class BaseTypes:

  var registry: TypeRegistry = new TypeRegistry()

  def read_value(value: Value): Double =
    val baseValue = value.base_value()
    val field = baseValue.index("value")
    val memory = ByteBuffer.wrap(baseValue.memory)
    memory.position(field.offset.toInt)

    field.valueType.t match
      case "byte" => memory.get().toDouble
      case "short" => memory.getShort().toDouble
      case "int" => memory.getInt().toDouble
      case "long" => memory.getLong().toDouble
      case "float" => memory.getFloat().toDouble
      case "double" => memory.getDouble()
      case typeName => throw new IllegalArgumentException(s"Base type '$typeName' does not define numeric storage")

  def write_value(value: Value, number: Double): Value =
    val baseValue = value.base_value()
    val field = baseValue.index("value")
    val memory = ByteBuffer.wrap(baseValue.memory)
    memory.position(field.offset.toInt)

    field.valueType.t match
      case "byte" => memory.put(number.toByte)
      case "short" => memory.putShort(number.toShort)
      case "int" => memory.putInt(number.toInt)
      case "long" => memory.putLong(number.toLong)
      case "float" => memory.putFloat(number.toFloat)
      case "double" => memory.putDouble(number)
      case typeName => throw new IllegalArgumentException(s"Base type '$typeName' does not define numeric storage")

    value

  def result_value(typeName: String, number: Double): Value =
    val result = new Value("result", Vector.empty, Map("value" -> typeName))
    result.registry = this.registry
    result.index_fields()
    result.allocate()
    this.write_value(result, number)

  def assign_value(left: Value, right: Value): Value =
    this.write_value(left, this.read_value(right))

  def numeric_value(left: Value, right: Value, operation: (Double, Double) => Double): Value =
    this.result_value(left.base_type_name(), operation(this.read_value(left), this.read_value(right)))

  def comparison_value(left: Value, right: Value, operation: (Double, Double) => Boolean): Value =
    this.result_value("byte", if operation(this.read_value(left), this.read_value(right)) then 1.0 else 0.0)

  def boolean_value(left: Value, right: Value, operation: (Boolean, Boolean) => Boolean): Value =
    val leftBoolean = this.read_value(left) != 0.0
    val rightBoolean = this.read_value(right) != 0.0
    this.result_value("byte", if operation(leftBoolean, rightBoolean) then 1.0 else 0.0)

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

        this.registry.register_operator(leftType, FunctionalId("add", argumentTypes), (a, arguments) => this.numeric_value(a, arguments(0), (left, right) => left + right))
        this.registry.register_operator(leftType, FunctionalId("+", argumentTypes), (a, arguments) => this.numeric_value(a, arguments(0), (left, right) => left + right))
        this.registry.register_operator(leftType, FunctionalId("subtract", argumentTypes), (a, arguments) => this.numeric_value(a, arguments(0), (left, right) => left - right))
        this.registry.register_operator(leftType, FunctionalId("-", argumentTypes), (a, arguments) => this.numeric_value(a, arguments(0), (left, right) => left - right))
        this.registry.register_operator(leftType, FunctionalId("multiply", argumentTypes), (a, arguments) => this.numeric_value(a, arguments(0), (left, right) => left * right))
        this.registry.register_operator(leftType, FunctionalId("*", argumentTypes), (a, arguments) => this.numeric_value(a, arguments(0), (left, right) => left * right))
        this.registry.register_operator(leftType, FunctionalId("divide", argumentTypes), (a, arguments) => this.numeric_value(a, arguments(0), (left, right) => left / right))
        this.registry.register_operator(leftType, FunctionalId("/", argumentTypes), (a, arguments) => this.numeric_value(a, arguments(0), (left, right) => left / right))
        this.registry.register_operator(leftType, FunctionalId("modulo", argumentTypes), (a, arguments) => this.numeric_value(a, arguments(0), (left, right) => left % right))
        this.registry.register_operator(leftType, FunctionalId("%", argumentTypes), (a, arguments) => this.numeric_value(a, arguments(0), (left, right) => left % right))

        this.registry.register_operator(leftType, FunctionalId("=", argumentTypes), (a, arguments) => this.assign_value(a, arguments(0)))
        this.registry.register_operator(leftType, FunctionalId("+=", argumentTypes), (a, arguments) => this.assign_value(a, this.numeric_value(a, arguments(0), (left, right) => left + right)))
        this.registry.register_operator(leftType, FunctionalId("-=", argumentTypes), (a, arguments) => this.assign_value(a, this.numeric_value(a, arguments(0), (left, right) => left - right)))
        this.registry.register_operator(leftType, FunctionalId("*=", argumentTypes), (a, arguments) => this.assign_value(a, this.numeric_value(a, arguments(0), (left, right) => left * right)))
        this.registry.register_operator(leftType, FunctionalId("/=", argumentTypes), (a, arguments) => this.assign_value(a, this.numeric_value(a, arguments(0), (left, right) => left / right)))
        this.registry.register_operator(leftType, FunctionalId("%=", argumentTypes), (a, arguments) => this.assign_value(a, this.numeric_value(a, arguments(0), (left, right) => left % right)))

        this.registry.register_operator(leftType, FunctionalId("<", argumentTypes), (a, arguments) => this.comparison_value(a, arguments(0), (left, right) => left < right))
        this.registry.register_operator(leftType, FunctionalId("<=", argumentTypes), (a, arguments) => this.comparison_value(a, arguments(0), (left, right) => left <= right))
        this.registry.register_operator(leftType, FunctionalId(">", argumentTypes), (a, arguments) => this.comparison_value(a, arguments(0), (left, right) => left > right))
        this.registry.register_operator(leftType, FunctionalId(">=", argumentTypes), (a, arguments) => this.comparison_value(a, arguments(0), (left, right) => left >= right))
        this.registry.register_operator(leftType, FunctionalId("==", argumentTypes), (a, arguments) => this.comparison_value(a, arguments(0), (left, right) => left == right))
        this.registry.register_operator(leftType, FunctionalId("!=", argumentTypes), (a, arguments) => this.comparison_value(a, arguments(0), (left, right) => left != right))
        this.registry.register_operator(leftType, FunctionalId("equals", argumentTypes), (a, arguments) => this.comparison_value(a, arguments(0), (left, right) => left == right))

        this.registry.register_operator(leftType, FunctionalId("&&", argumentTypes), (a, arguments) => this.boolean_value(a, arguments(0), (left, right) => left && right))
        this.registry.register_operator(leftType, FunctionalId("||", argumentTypes), (a, arguments) => this.boolean_value(a, arguments(0), (left, right) => left || right))

        rightTypeIndex += 1

      val unaryArgument = Map("a" -> leftType)
      this.registry.register_operator(leftType, FunctionalId("!", unaryArgument), (a, _) => this.result_value("byte", if this.read_value(a) == 0.0 then 1.0 else 0.0))
      this.registry.register_operator(leftType, FunctionalId("unary+", unaryArgument), (a, _) => this.result_value(leftType, this.read_value(a)))
      this.registry.register_operator(leftType, FunctionalId("unary-", unaryArgument), (a, _) => this.result_value(leftType, -this.read_value(a)))

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
