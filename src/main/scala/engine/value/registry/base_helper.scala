// Bootstrap pack for the singular C-style computer types.
// A TypeRegistry starts empty; this pack explicitly adds sizes and lambda operators.
package value

import java.nio.ByteBuffer

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

    this.registry.register_cast("byte", bytes => bytes(0).toDouble, number => Array(number.toByte))
    this.registry.register_cast("short", bytes => ByteBuffer.wrap(bytes).getShort().toDouble, number => ByteBuffer.allocate(2).putShort(number.toShort).array())
    this.registry.register_cast("int", bytes => ByteBuffer.wrap(bytes).getInt().toDouble, number => ByteBuffer.allocate(4).putInt(number.toInt).array())
    this.registry.register_cast("long", bytes => ByteBuffer.wrap(bytes).getLong().toDouble, number => ByteBuffer.allocate(8).putLong(number.toLong).array())
    this.registry.register_cast("float", bytes => ByteBuffer.wrap(bytes).getFloat().toDouble, number => ByteBuffer.allocate(4).putFloat(number.toFloat).array())
    this.registry.register_cast("double", bytes => ByteBuffer.wrap(bytes).getDouble(), number => ByteBuffer.allocate(8).putDouble(number).array())

    val addOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.cast(id.arguments("a"), left + right)

    val subtractOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.cast(id.arguments("a"), left - right)

    val multiplyOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.cast(id.arguments("a"), left * right)

    val divideOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.cast(id.arguments("a"), left / right)

    val moduloOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.cast(id.arguments("a"), left % right)

    val assignOperator: OperatorFunction = (id, a, arguments) =>
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.insert(id.arguments("a"), a, right)

    val addAssignOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.insert(id.arguments("a"), a, left + right)

    val subtractAssignOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.insert(id.arguments("a"), a, left - right)

    val multiplyAssignOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.insert(id.arguments("a"), a, left * right)

    val divideAssignOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.insert(id.arguments("a"), a, left / right)

    val moduloAssignOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.insert(id.arguments("a"), a, left % right)

    val lessOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.cast("byte", if left < right then 1.0 else 0.0)

    val lessEqualOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.cast("byte", if left <= right then 1.0 else 0.0)

    val greaterOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.cast("byte", if left > right then 1.0 else 0.0)

    val greaterEqualOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.cast("byte", if left >= right then 1.0 else 0.0)

    val equalOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.cast("byte", if left == right then 1.0 else 0.0)

    val notEqualOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a)
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0))
      this.registry.caster.cast("byte", if left != right then 1.0 else 0.0)

    val andOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a) != 0.0
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0)) != 0.0
      this.registry.caster.cast("byte", if left && right then 1.0 else 0.0)

    val orOperator: OperatorFunction = (id, a, arguments) =>
      val left = this.registry.caster.retrieve(id.arguments("a"), a) != 0.0
      val right = this.registry.caster.retrieve(id.arguments("b"), arguments(0)) != 0.0
      this.registry.caster.cast("byte", if left || right then 1.0 else 0.0)

    def register_pair(leftType: String, rightType: String): Unit =
      val argumentTypes = Map("a" -> leftType, "b" -> rightType)
      this.registry.register_operator(leftType, FunctionalId("add", argumentTypes), addOperator)
      this.registry.register_operator(leftType, FunctionalId("+", argumentTypes), addOperator)
      this.registry.register_operator(leftType, FunctionalId("subtract", argumentTypes), subtractOperator)
      this.registry.register_operator(leftType, FunctionalId("-", argumentTypes), subtractOperator)
      this.registry.register_operator(leftType, FunctionalId("multiply", argumentTypes), multiplyOperator)
      this.registry.register_operator(leftType, FunctionalId("*", argumentTypes), multiplyOperator)
      this.registry.register_operator(leftType, FunctionalId("divide", argumentTypes), divideOperator)
      this.registry.register_operator(leftType, FunctionalId("/", argumentTypes), divideOperator)
      this.registry.register_operator(leftType, FunctionalId("modulo", argumentTypes), moduloOperator)
      this.registry.register_operator(leftType, FunctionalId("%", argumentTypes), moduloOperator)
      this.registry.register_operator(leftType, FunctionalId("=", argumentTypes), assignOperator)
      this.registry.register_operator(leftType, FunctionalId("+=", argumentTypes), addAssignOperator)
      this.registry.register_operator(leftType, FunctionalId("-=", argumentTypes), subtractAssignOperator)
      this.registry.register_operator(leftType, FunctionalId("*=", argumentTypes), multiplyAssignOperator)
      this.registry.register_operator(leftType, FunctionalId("/=", argumentTypes), divideAssignOperator)
      this.registry.register_operator(leftType, FunctionalId("%=", argumentTypes), moduloAssignOperator)
      this.registry.register_operator(leftType, FunctionalId("<", argumentTypes), lessOperator)
      this.registry.register_operator(leftType, FunctionalId("<=", argumentTypes), lessEqualOperator)
      this.registry.register_operator(leftType, FunctionalId(">", argumentTypes), greaterOperator)
      this.registry.register_operator(leftType, FunctionalId(">=", argumentTypes), greaterEqualOperator)
      this.registry.register_operator(leftType, FunctionalId("==", argumentTypes), equalOperator)
      this.registry.register_operator(leftType, FunctionalId("!=", argumentTypes), notEqualOperator)
      this.registry.register_operator(leftType, FunctionalId("equals", argumentTypes), equalOperator)
      this.registry.register_operator(leftType, FunctionalId("&&", argumentTypes), andOperator)
      this.registry.register_operator(leftType, FunctionalId("||", argumentTypes), orOperator)

    register_pair("byte", "byte")
    register_pair("byte", "short")
    register_pair("byte", "int")
    register_pair("byte", "long")
    register_pair("byte", "float")
    register_pair("byte", "double")
    register_pair("short", "byte")
    register_pair("short", "short")
    register_pair("short", "int")
    register_pair("short", "long")
    register_pair("short", "float")
    register_pair("short", "double")
    register_pair("int", "byte")
    register_pair("int", "short")
    register_pair("int", "int")
    register_pair("int", "long")
    register_pair("int", "float")
    register_pair("int", "double")
    register_pair("long", "byte")
    register_pair("long", "short")
    register_pair("long", "int")
    register_pair("long", "long")
    register_pair("long", "float")
    register_pair("long", "double")
    register_pair("float", "byte")
    register_pair("float", "short")
    register_pair("float", "int")
    register_pair("float", "long")
    register_pair("float", "float")
    register_pair("float", "double")
    register_pair("double", "byte")
    register_pair("double", "short")
    register_pair("double", "int")
    register_pair("double", "long")
    register_pair("double", "float")
    register_pair("double", "double")

    val notOperator: OperatorFunction = (id, a, _) =>
      val value = this.registry.caster.retrieve(id.arguments("a"), a)
      this.registry.caster.cast("byte", if value == 0.0 then 1.0 else 0.0)

    val unaryAddOperator: OperatorFunction = (id, a, _) =>
      val value = this.registry.caster.retrieve(id.arguments("a"), a)
      this.registry.caster.cast(id.arguments("a"), value)

    val unarySubtractOperator: OperatorFunction = (id, a, _) =>
      val value = this.registry.caster.retrieve(id.arguments("a"), a)
      this.registry.caster.cast(id.arguments("a"), -value)

    def register_unary(typeName: String): Unit =
      val argumentTypes = Map("a" -> typeName)
      this.registry.register_operator(typeName, FunctionalId("!", argumentTypes), notOperator)
      this.registry.register_operator(typeName, FunctionalId("unary+", argumentTypes), unaryAddOperator)
      this.registry.register_operator(typeName, FunctionalId("unary-", argumentTypes), unarySubtractOperator)

    register_unary("byte")
    register_unary("short")
    register_unary("int")
    register_unary("long")
    register_unary("float")
    register_unary("double")

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
