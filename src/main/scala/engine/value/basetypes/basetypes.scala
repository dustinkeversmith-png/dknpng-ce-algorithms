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
    typeSizes(name) = size
    operators(name) = operator

