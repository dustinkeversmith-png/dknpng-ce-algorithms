import scala.collection.mutable.HashMap



// Register of all base type sizes
final class TypeRegistry:

  var caster: Caster = new Caster(this)

  // A map for mapping type names eg the t variable in a ValueType, not the name of the value but the name of the type etc.
  var sizes: HashMap[String, Long] = HashMap.empty

  // This is our operators map, it stores all of the OperatorBases per type name, see Operators.
  // The bootstrap implementation now stores ValueOperators in that same per-type position.
  var operators: HashMap[String, ValueOperators] = HashMap.empty

  def register_type(name: String, size: Long): Unit =
    require(name.nonEmpty, "A base type name cannot be empty")
    require(size > 0, "A base type must occupy at least one byte")
    this.sizes(name) = size

    val registeredOperators = new ValueOperators()
    this.operators(name) = registeredOperators

  def register_operator(name: String, id: FunctionalId, operator: OperatorFunction): Unit =
    this.operators.getOrElse(
      name,
      throw new NoSuchElementException(s"Register type '$name' before registering its operators")
    ).register(id, operator)

  def register_cast(name: String, retrieve: Array[Byte] => Double, insert: Double => Array[Byte]): Unit =
    this.caster.register(name, retrieve, insert)

  def contains(name: String): Boolean = this.sizes.contains(name)

  def operator(name: String, values: Array[Value]): Value =
    require(values.nonEmpty, s"Operator '$name' requires at least one Value")

    val baseValue = values(0).base_value()
    val typeName = baseValue.base_type_name()
    var valueArguments: Vector[Value] = Vector(baseValue)
    var argumentIndex = 1

    while argumentIndex < values.length do
      valueArguments = valueArguments :+ values(argumentIndex)
      argumentIndex += 1

    this.operators.getOrElse(
      typeName,
      throw new NoSuchElementException(s"No operators are registered for type '$typeName'")
    ).operator(name, valueArguments)

  def size(name: String): Long =
    this.sizes.getOrElse(name, throw new NoSuchElementException(s"Unknown base type: $name"))




