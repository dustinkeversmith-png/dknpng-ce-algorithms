import scala.collection.mutable.HashMap



// Register of all base type sizes
final class TypeRegistry:
  

  // A map for mapping type names eg the t variable in a ValueType, not the name of the value but the name of the type etc.
  var sizes: HashMap[String, Long] =
    HashMap()

  // This is our operators map, it stores all of the OperatorBases per type name, see Operators.
  val operators: HashMap[String, ValueOperators] =
    HashMap.empty

  def registerType(
    name: String,
    size: Long,
    operator: BaseTypeOperator
  ): Unit =
    require(name.nonEmpty, "A base type name cannot be empty")
    require(size > 0, "A base type must occupy at least one byte")
    typeSizes(name) = size
    operators(name) = operator


  def contains(name: String): Boolean = typeSizes.contains(name)
