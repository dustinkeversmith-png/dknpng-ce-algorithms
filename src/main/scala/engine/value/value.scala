
// 1. Import the mutable package
import scala.collection.mutable.HashMap



// A value type description specifying a recursive fields map containing 1 or many nested ValueTypes
// The type_name is a base type defined then recursion breaks when attempting to iterate.
trait ValueType

  // Recursive named contents
  def fields: Map[String, ValueType] = Map.empty

  // Canonical name for this value type, is registered as a base type if it is a leaf style node such as [int, double] etc or other custom registered types.
  def t: String


// The actual data container for the value type description.
class Value extends ValueType 

  // Canoncial name of the value itself.
  def name: String

  // Memory representing the nested recursive field description, used for subindexing and pased into basetype operators for calculation.
  def memory: Any = ???

  // Vector dimensionality if = (1) its simply just one ValueType memory at memories, but if its (10) then its a vector like contiguous shape in dimension and so on and so on of the memories.
  
  // Shape describes the dimensionality of this value type so it could be like [2][2] meaning it has like a set standard dimensionality of itself 2x2 as a matrix 
  def shape: Vector[Int] = Vector.empty

  // Collects details such as a map of the field names to their internal offsets and lengths in the memory
  // Creates a self size map for ease of allocation and use, and total size for contiguous iteration.
  def detail_self():

  // Allocates enough memory to store the necessary value types in size.
  // Requires recursive collection of type name size references for accumulation, in the process can collect helper indexers
  def allocate(): Unit = ???

  // Used to define iteration travel in directions, such as next, prev, or arbitrary directions for neighbor hood searches in the dimensionality space
  def iterate_dimension():
 
  // Used to aid in iterating through the different sub types such as fields within an actual value.
  def iterate_value():

  // So access and iterator can be accessed [1-N][1_N_2] for the initial dimensionality, then it will finally be able to access and iterate each type, by measuring the strides, and recursive strides into the object.


  
  // Meaning we have ValueType[] -> Represented as the n dimensional version of the shape.

  




// Ideally also a casting mechanism or atleast some operator registry on the base types.

// Be like I can register types programmitaclly, define their behavior, etc etc, automatically cast and do operations on the lowest level base values



class TypeTests extends munit.FunSuite {
  test("test type creation and base functional iteration, type and size natures.") {

    
    val particle =
      Value(
        name = "Particle",
        shape = [5]

        fields = Map(
          "id"       -> "long",
          "mass"     -> "double",
          "position" -> 
          ValueTypeImpl(
            name = "Array",
            shape = [3],
            fields = Map(
                "value" -> "int"
            )
          )
          
        ),

      )

    // Accessor types.
    particle.push({"particle_values"})
    particle[2]["mass"]

    Value particle_mass;

    Iter p_iter = particle[0].iterator();

    While( p_iter.value.name != "mass" ) p_iter.next()

    // Then p_iter will have the offset and length in the memory of the value stuff.


  }
}


// Then when building a type you can like create a base type, and just append shit to the map, and register base type sizes, and or reuse any base operations on the base types.