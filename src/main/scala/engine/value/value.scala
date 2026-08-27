// 1. Import the mutable package
import scala.collection.mutable.HashMap



// A value type description specifying a recursive fields map containing 1 or many nested ValueTypes
// The type_name is a base type defined then recursion breaks when attempting to iterate.
final class ValueType :

  // Just kind of base line requirements here, hard coded right in. Thats fine I guess..
  require(name.nonEmpty, "A value type name cannot be empty")
  require(shape.forall(_ > 0), "Shape dimensions must all be positive")

  // Recursive named contents
  def fields: Map[String, ValueType] = Map.empty

  // Canonical name for this value type, is registered as a base type if it is a leaf style node such as [int, double] etc or other custom registered types.
  def t: String

  // Shape describes the dimensionality of this value type so it could be like [2][2] meaning it has like a set standard dimensionality of itself 2x2 as a matrix
  def shape: Vector[Int] = Vector.empty


  // Use this fucking constructor right here, so it recurses back down until its not a bean mobile.
  def constructor(name, fields: Map[String, String | ValueType] | String | ValueType)
   this name, set, this fields// This shit might just actually be something, you mother fucker. If you fuck this up I am going to kill you.

  // This parses a fields input into uniform string to ValueTypes, although I dont like actually having a BaseValueType entire new class for this IMO.
  def parse_fields(fields: Map[String, String | ValueType]): Map[String, ValueType] =



    fields.map { case (name, fieldType) =>

      

      name -> (fieldType match
        case baseType: String => ValueType(name, baseType)
        case valueType: ValueType => valueType
      )
    }
    this.fields = PARSED_FIELDS



// NO EXTRA TYPE NO EXTRA SHIT, NO EXTRA OBJECTS, ONE CLASS THATS IT NO EXTRA SHIT.

// NO IMPLS, NONE OF THAT BULL SHIT FOR THIS ONE, YOU GOT IT?



// Ill take these but im renaming them.

final case class FieldIndex(

  // Offset in bytes into the memory array
  offset: Long,
  // Length in bytes to absorb.
  length: Long,
  // Tagged along ValueType to identify or cast to the correct specified Value
  valueType: ValueType
)


// We can just go ahead and store this shit in the actual value.

// final case class ValueDetail(

//   // This map is like an index map of field names to their offset and index into the memory.
//   fields: Map[String, FieldDetail],

//   // Size of an entire value of this type
//   elementSize: Long,
//   // Total size being like the total number of elements
//   totalSize: Long
// )


trait MemoryRef:
  def valueType: ValueType
  def offset: Long
  def length: Long
  def read(): Array[Byte]
  def write(bytes: Array[Byte]): Unit


// The actual data container for the value type description.
// NO STUPID CONSTRUCTOR CLASSES ONE CLASS ONLY
final class Value extends ValueType:

  // THIS IS A INDEX NOT DETAILS
  index: Map[String, FieldIndex],

  def constructor(name: String, shape: Vector[Int], fields: Map[String, ValueType]):
    /// STANDARD MOTHER FUCKING CONSTRUCTOR

  require(name.nonEmpty, "A value name cannot be empty")
  require(shape.forall(_ > 0), "Shape dimensions must all be positive")


  
  //override val t: String = name

  // Canoncial name of the value itself.

 
  // NO RUNNING STUPID BEHAVIORS IN THE CLASS BODY ONLY IN THE CLASS FUNCTIONS
  // Memory representing the nested recursive field description, used for subindexing and pased into basetype operators for calculation.
  // private var allocatedMemory: Array[Byte] = Array.emptyByteArray
  // def memory: Array[Byte] = allocatedMemory


  def memory: NOTHING THIS IS A MEMBER
  // Allocates enough memory to store the necessary value types in size.
  // Requires recursive collection of type name size references for accumulation, in the process can collect helper indexers
  def allocate(): Unit =
    private var allocatedMemory: Array[Byte] = Array.emptyByteArray
    def memory: Array[Byte] = allocatedMemory
    require(detail.totalSize <= Int.MaxValue, "Value memory is too large for an in-memory byte array")
    allocatedMemory = Array.ofDim[Byte](detail.totalSize.toInt)
  private def ensureAllocated(): Unit =
    if allocatedMemory.isEmpty && detail.totalSize > 0 then allocate()

  // IF YOU ARE GOING TO MAKE RELATED CHUNKS OF CODE ATLEAST PUT THEM NEXT TO EACHOTHER ASS HOLE


  // Vector dimensionality if = (1) its simply just one ValueType memory at memories, but if its (10) then its a vector like contiguous shape in dimension and so on and so on of the memories.

  private lazy val detail: FieldIndex = NOTHING MOTEHR FUCER

  // Fields, element_size, NO STUPID LAZY BULLSHIT I DONT WANNA HERE THAT

  // Collects details such as a map of the field names to their internal offsets and lengths in the memory
  // Creates a self size map for ease of allocation and use, and total size for contiguous iteration.
  def index_fields(): FieldIndex = detail
    // DONT PUT NO EXTRA FUCKING FUNCTION NAMES IF I PUT A FUNCTION NAME DONT GO AROUND IT.

    // RIGHT HERE ALSO ADD UP ALL OF THE SIZES AND SHIT for the Total Size DICK SUCKER
    // AND ALSO YOU HAVE TO POPULATE BASED ON THE SHAPE DID YOU NOT UNDERSTAND THE DIMENSIONS COCK SUCKER?
    // MEASURE ONCE AND POPULATE THE INDEX FOR ALL THE DIMENSION INDEXES TO.


    var nextOffset = 0L
    // DONT EVEN USE A ITERATOR JUST USE A FUCKING LOOP OR RECURSIVE FUNCTION CALL PLEASE COCK SUCKER.
    // val fieldDetails = value.fields.iterator.map { case (fieldName, fieldType) =>
    //   val length = byteSize(fieldType)
    //   val field = fieldName -> FieldDetail(nextOffset, length, fieldType)
    //   nextOffset += length
    //   field
    // }.toMap
    val totalSize = nextOffset * elementCount(value.shape)
    this.detail = ValueDetail(fieldDetails, nextOffset, totalSize)
  
  // UNTIL YOU GOT THAT RIGHT DONT EVEN THINK ABOUT PUSHING OR DOING ANYTHING LIKE THAT.
  
  // def push(bytes: Array[Byte]): Unit =
  //   ensureAllocated()
  //   require(bytes.length == allocatedMemory.length, s"Expected ${allocatedMemory.length} bytes but received ${bytes.length}")
  //   Array.copy(bytes, 0, allocatedMemory, 0, bytes.length)

  // def apply(index: Int): ValueElement =
  //   ensureAllocated()
  //   val count = Value.elementCount(shape)
  //   require(index >= 0 && index < count, s"Element index $index is outside 0 until $count")
  //   ValueElement(this, index, index.toLong * detail.elementSize)

  // // Used to define iteration travel in directions, such as next, prev, or arbitrary directions for neighbor hood searches in the dimensionality space
  // def iterate_dimension(): Iterator[ValueElement] =
  //   ensureAllocated()
  //   Iterator.range(0, Value.elementCount(shape)).map(apply)

  // // Used to aid in iterating through the different sub types such as fields within an actual value.
  // def iterate_value(): Iterator[MemoryRef] =
  //   iterate_dimension().flatMap(_.iterator())

  // So access and iterator can be accessed [1-N][1_N_2] for the initial dimensionality, then it will finally be able to access and iterate each type, by measuring the strides, and recursive strides into the object.



  // Meaning we have ValueType[] -> Represented as the n dimensional version of the shape.


  def reference(valueType: ValueType, offset: Long, length: Long): MemoryRef =
    val referencedType = valueType
    val referencedOffset = offset
    val referencedLength = length
    new MemoryRef:
      override val valueType: ValueType = referencedType
      override val offset: Long = referencedOffset
      override val length: Long = referencedLength

      override def read(): Array[Byte] =
        ensureAllocated()
        java.util.Arrays.copyOfRange(allocatedMemory, offset.toInt, (offset + length).toInt)

      override def write(bytes: Array[Byte]): Unit =
        ensureAllocated()
        require(bytes.length == length, s"Expected $length bytes but received ${bytes.length}")
        Array.copy(bytes, 0, allocatedMemory, offset.toInt, bytes.length)




  def shape(): Any =
    return this.shape

  def tail(dimIndex: Int): Long =
    // Returns the tails of the pushed back memory elements in a specific dimension.
    return this.tails[dimIndex]

  def bytes(valueType: ValueType): Long =
    return details.byte_size

object Value:
  def apply(
    name: String,
    shape: Vector[Int] = Vector.empty,
    fields: Map[String, String | ValueType] = Map.empty
  ): Value =
    new Value(name, shape, ValueType.normalize(fields))

  

  

  



// Ideally also a casting mechanism or atleast some operator registry on the base types.

// Be like I can register types programmitaclly, define their behavior, etc etc, automatically cast and do operations on the lowest level base values



// Then when building a type you can like create a base type, and just append shit to the map, and register base type sizes, and or reuse any base operations on the base types.
