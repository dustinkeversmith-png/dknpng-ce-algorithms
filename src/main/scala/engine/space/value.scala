
// 1. Import the mutable package
import scala.collection.mutable.HashMap

trait BaseTypeOperator:

  def cast(value: Any, targetType: String): Any

  def add(a: Any, b: Any): Any

  def subtract(a: Any, b: Any): Any

  def multiply(a: Any, b: Any): Any

  def divide(a: Any, b: Any): Any


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



trait ValueType

  // FieldName -> FieldType // If FieldType == Value then have to go to collect the leaf node.
  def fields: Map[String, String]

  // Recursive named contents
  def values: Map[String, ValueType] = Map.empty

  def t: String


class Value extends ValueType 


  // Represents the memory chunk represented allocated for the shaped valueType
  def memory: Any = ???

  // So access and iterator can be accessed [1-N][1_N_2] for the initial dimensionality, then it will finally be able to access and iterate each type, by measuring the strides, and recursive strides into the object.


 // Shape describes the dimensionality of this value type so it could be like [2][2] meaning it has like a set standard dimensionality of itself 2x2 as a matrix 
  def shape: Vector[Int] = Vector.empty
  // Meaning we have ValueType[] -> Represented as the n dimensional version of the shape.

  // Allocates the necessary memory based on the stride of the traversed ValueType Map / Tree
  def allocate(): Unit = ???




// Ideally also a casting mechanism or atleast some operator registry on the base types.

// Be like I can register types programmitaclly, define their behavior, etc etc, automatically cast and do operations on the lowest level base values

val typeInfo =
  Value(
    t = "Particle",
    shape = 1

    fields = Map(
      "id"       -> "long",
      "mass"     -> "double",
      "position" -> 
      
       ValueTypeImpl(
        t = "Array",
        shape = [3],
        fields = Map(
            "value" -> "int"
        )
       )
      
    ),

  )

val typeInfo =
  Value(
    t = "Particle",

    fields = Map(
      "id"       -> "long",
      "mass"     -> "double",
      "position" -> "double[]"
    ),

  )


// Then when building a type you can like create a base type, and just append shit to the map, and register base type sizes, and or reuse any base operations on the base types.