import scala.collection.mutable.HashMap



// Register of all base type sizes
final class TypeRegistry:

  // A map for mapping type names eg the t variable in a ValueType, not the name of the value but the name of the type etc.
  var sizes: HashMap[String, Long] = HashMap.empty

  // This is our operators map, it stores all of the OperatorBases per type name, see Operators.
  // The bootstrap implementation now stores ValueOperators in that same per-type position.
  var operators: HashMap[String, ValueOperators] = HashMap.empty

  def register_type(name: String, size: Long): 
    require(name.nonEmpty, "A base type name cannot be empty")
    require(size > 0, "A base type must occupy at least one byte")
    this.sizes(name) = size

    val registeredOperators = new ValueOperators()
    this.operators(name) = registeredOperators

  def register_operator(name: String, FunctionalId id, OperatorFunction):
    this.operators(name).register()

  def contains(name: String): Boolean = this.sizes.contains(name)

  def size(name: String): Long =
    this.sizes.getOrElse(name, throw new NoSuchElementException(s"Unknown base type: $name"))




