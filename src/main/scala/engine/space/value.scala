
// 1. Import the mutable package
import scala.collection.mutable.HashMap

// So then I need a parser for the function which I want to be a string kind of or at least some kind of composable functional since the value types are accessible by string in the actual value these can obviously be expressed as intermediary stack, and base operations, assuming some BaseTypeOperator is always defined for the sub type.

trait FunctionalAst:
    // Then probably each line has its own Ast, it executes mean while mutating the stack
    // Then moving on, and then pushes that all back into the type maybe?
    Map[string, Value] stack

trait Functional:
    Map[string, Value] stack
    Vector[string: args, operators: "", or like ast type thing but a program ast so it has access to stack or declaritive intermediary values that can be resampled]



trait BaseTypeOperator:

  // Map[String, Functional(Value a, Value b, ... However many values) -> Value] operator_set

  // Then can map independent operator overrides to the operator_set as functions.

  ["cast"] = 
    Functional(
        // So assuming we know the underlying name of a function say value, we then accept the line seperations, then it goes and executes this with a functional ast
        "value += other_value * 2.0"
        "intermediary ="

    )
    
    (value: Any, targetType: String): Any

  ["add"](a: Any, b: Any): Any

  ["subtract"](a: Any, b: Any): Any

  ["multiply"](a: Any, b: Any): Any

  ["divide"](a: Any, b: Any): Any


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