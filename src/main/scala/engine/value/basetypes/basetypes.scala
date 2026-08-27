import scala.collection.mutable.HashMap

// Base type operations intentionally remain outside the first value-system milestone.
trait BaseTypeOperator

// Register of all base type sizes
object TypeRegistry:

  val typeSizes: HashMap[String, Long] =
    HashMap(
      "byte"   -> 1L,
      "short"  -> 2L,
      "int"    -> 4L,
      "long"   -> 8L,
      "float"  -> 4L,
      "double" -> 8L
    )

  val operators: HashMap[String, BaseTypeOperator] =
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

  def byteSize(name: String): Long =
    typeSizes.getOrElse(name, throw new NoSuchElementException(s"Unknown base type: $name"))

  def contains(name: String): Boolean = typeSizes.contains(name)
