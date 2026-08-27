// 1. Import the mutable package
import scala.collection.mutable.HashMap



// A value type description specifying a recursive fields map containing 1 or many nested ValueTypes
// The type_name is a base type defined then recursion breaks when attempting to iterate.
class ValueType(
  var name: String,
  var t: String,
  var shape: Vector[Int],
  var fields: Map[String, ValueType]
):

  // Just kind of base line requirements here, hard coded right in. Thats fine I guess..
  require(this.name.nonEmpty, "A value type name cannot be empty")
  require(this.t.nonEmpty, "A canonical value type name cannot be empty")
  require(this.shape.forall(_ > 0), "Shape dimensions must all be positive")

  // Recursive named contents

  // Canonical name for this value type, is registered as a base type if it is a leaf style node such as [int, double] etc or other custom registered types.

  // Shape describes the dimensionality of this value type so it could be like [2][2] meaning it has like a set standard dimensionality of itself 2x2 as a matrix

  var element_size: Long = 0L
  var byte_size: Long = 0L


  // Use this fucking constructor right here, so it recurses back down until its not a bean mobile.
  def this(name: String, baseType: String) =
    this(name, baseType, Vector.empty, Map.empty)
    require(TypeRegistry.typeSizes.contains(baseType), s"Unknown base type: $baseType")
   // this name, set, this fields
   // This shit might just actually be something, you mother fucker. If you fuck this up I am going to kill you.

  def this(name: String, fields: Map[String, String | ValueType]) =
    this(name, name, Vector.empty, Map.empty)
    this.parse_fields(fields)

  def this(name: String, shape: Vector[Int], fields: Map[String, String | ValueType]) =
    this(name, name, shape, Map.empty)
    this.parse_fields(fields)

  // This parses a fields input into uniform string to ValueTypes, although I dont like actually having a BaseValueType entire new class for this IMO.
  def parse_fields(fields: Map[String, String | ValueType]): Map[String, ValueType] =
    var parsedFields: Map[String, ValueType] = Map.empty
    val fieldNames = fields.keys.toVector
    var fieldIndex = 0

    while fieldIndex < fieldNames.length do
      val fieldName = fieldNames(fieldIndex)
      val fieldType = fields(fieldName)

      fieldType match
        case baseType: String =>
          parsedFields = parsedFields.updated(fieldName, new ValueType(fieldName, baseType))
        case valueType: ValueType =>
          parsedFields = parsedFields.updated(fieldName, valueType)

      fieldIndex += 1

    this.fields = parsedFields
    this.fields



  override def equals(other: Any): Boolean =
    other match
      case that: ValueType =>
        if this.name != that.name then return false
        if this.t != that.t then return false
        if this.shape.length != that.shape.length then return false

        var shapeIndex = 0
        while shapeIndex < this.shape.length do
          if this.shape(shapeIndex) != that.shape(shapeIndex) then return false
          shapeIndex += 1

        if this.fields.size != that.fields.size then return false

        val thisFieldNames = this.fields.keys.toVector.sorted
        val thatFieldNames = that.fields.keys.toVector.sorted
        var fieldIndex = 0

        while fieldIndex < thisFieldNames.length do
          val thisFieldName = thisFieldNames(fieldIndex)
          val thatFieldName = thatFieldNames(fieldIndex)
          if thisFieldName != thatFieldName then return false

          val thisFieldType = this.fields(thisFieldName)
          val thatFieldType = that.fields(thatFieldName)
          if !thisFieldType.equals(thatFieldType) then return false

          fieldIndex += 1

        true
      case _ => false

  override def hashCode(): Int =
    // The hash is encoded explicitly in this order: name characters, canonical type characters,
    // each shape dimension, then each alphabetically ordered field name and its recursive ValueType hash.
    var hash = 17
    var characterIndex = 0

    while characterIndex < this.name.length do
      hash = 31 * hash + this.name.charAt(characterIndex).toInt
      characterIndex += 1

    characterIndex = 0
    while characterIndex < this.t.length do
      hash = 31 * hash + this.t.charAt(characterIndex).toInt
      characterIndex += 1

    var shapeIndex = 0
    while shapeIndex < this.shape.length do
      hash = 31 * hash + this.shape(shapeIndex)
      shapeIndex += 1

    val fieldNames = this.fields.keys.toVector.sorted
    var fieldIndex = 0
    while fieldIndex < fieldNames.length do
      val fieldName = fieldNames(fieldIndex)
      characterIndex = 0

      while characterIndex < fieldName.length do
        hash = 31 * hash + fieldName.charAt(characterIndex).toInt
        characterIndex += 1

      hash = 31 * hash + this.fields(fieldName).hashCode()
      fieldIndex += 1

    hash



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
final class Value(
  name: String,
  shape: Vector[Int],
  fields: Map[String, String | ValueType]
) extends ValueType(name, shape, fields):

  // THIS IS A INDEX NOT DETAILS
  var index: Map[String, FieldIndex] = Map.empty

  /// STANDARD MOTHER FUCKING CONSTRUCTOR

  require(this.name.nonEmpty, "A value name cannot be empty")
  require(this.shape.forall(_ > 0), "Shape dimensions must all be positive")



  //override val t: String = name

  // Canoncial name of the value itself.


  // NO RUNNING STUPID BEHAVIORS IN THE CLASS BODY ONLY IN THE CLASS FUNCTIONS
  // Memory representing the nested recursive field description, used for subindexing and pased into basetype operators for calculation.
  // private var allocatedMemory: Array[Byte] = Array.emptyByteArray
  // def memory: Array[Byte] = allocatedMemory


  var memory: Array[Byte] = Array.emptyByteArray
  var total_size: Long = 0L
  var tails: Vector[Long] = Vector.empty

  // Allocates enough memory to store the necessary value types in size.
  // Requires recursive collection of type name size references for accumulation, in the process can collect helper indexers
  def allocate(): Unit =
    require(this.total_size > 0, "index_fields() must measure the value before allocate()")
    require(this.total_size <= Int.MaxValue, "Value memory is too large for an in-memory byte array")
    this.memory = Array.ofDim[Byte](this.total_size.toInt)

  // IF YOU ARE GOING TO MAKE RELATED CHUNKS OF CODE ATLEAST PUT THEM NEXT TO EACHOTHER ASS HOLE


  // Vector dimensionality if = (1) its simply just one ValueType memory at memories, but if its (10) then its a vector like contiguous shape in dimension and so on and so on of the memories.

  // Fields, element_size, NO STUPID LAZY BULLSHIT I DONT WANNA HERE THAT

  // Collects details such as a map of the field names to their internal offsets and lengths in the memory
  // Creates a self size map for ease of allocation and use, and total size for contiguous iteration.
  def index_fields(): Map[String, FieldIndex] =
    // DONT PUT NO EXTRA FUCKING FUNCTION NAMES IF I PUT A FUNCTION NAME DONT GO AROUND IT.

    // RIGHT HERE ALSO ADD UP ALL OF THE SIZES AND SHIT for the Total Size DICK SUCKER
    // AND ALSO YOU HAVE TO POPULATE BASED ON THE SHAPE DID YOU NOT UNDERSTAND THE DIMENSIONS COCK SUCKER?
    // MEASURE ONCE AND POPULATE THE INDEX FOR ALL THE DIMENSION INDEXES TO.

    this.index = Map.empty

    def measure(valueType: ValueType): Long =
      var measuredElementSize = 0L

      if valueType.fields.isEmpty then
        measuredElementSize = TypeRegistry.typeSizes.getOrElse(
          valueType.t,
          throw new NoSuchElementException(s"Unknown base type: ${valueType.t}")
        )
      else
        val fieldNames = valueType.fields.keys.toVector
        var fieldIndex = 0

        while fieldIndex < fieldNames.length do
          val fieldName = fieldNames(fieldIndex)
          measuredElementSize += measure(valueType.fields(fieldName))
          fieldIndex += 1

      var shapedSize = measuredElementSize
      var shapeIndex = 0
      while shapeIndex < valueType.shape.length do
        shapedSize *= valueType.shape(shapeIndex).toLong
        shapeIndex += 1

      valueType.element_size = measuredElementSize
      valueType.byte_size = shapedSize
      valueType.byte_size

    def populate(valueType: ValueType, startOffset: Long, path: String): Unit =
      def populateElement(elementOffset: Long, elementPath: String): Unit =
        if valueType.fields.nonEmpty then
          var nextOffset = elementOffset
          val fieldNames = valueType.fields.keys.toVector
          var fieldIndex = 0

          while fieldIndex < fieldNames.length do
            val fieldName = fieldNames(fieldIndex)
            val fieldType = valueType.fields(fieldName)
            val fieldPath =
              if elementPath.isEmpty then fieldName
              else s"$elementPath.$fieldName"

            this.index = this.index.updated(
              fieldPath,
              FieldIndex(nextOffset, fieldType.byte_size, fieldType)
            )

            if fieldType.fields.nonEmpty || fieldType.shape.nonEmpty then
              populate(fieldType, nextOffset, fieldPath)

            nextOffset += fieldType.byte_size
            fieldIndex += 1

      def populateDimensions(
        dimensionIndex: Int,
        linearIndex: Long,
        dimensionPath: String
      ): Unit =
        if dimensionIndex == valueType.shape.length then
          val elementOffset = startOffset + linearIndex * valueType.element_size
          val completePath = path + dimensionPath

          if dimensionPath.nonEmpty then
            this.index = this.index.updated(
              completePath,
              FieldIndex(elementOffset, valueType.element_size, valueType)
            )

          populateElement(elementOffset, completePath)
        else
          var coordinate = 0
          while coordinate < valueType.shape(dimensionIndex) do
            populateDimensions(
              dimensionIndex + 1,
              linearIndex * valueType.shape(dimensionIndex).toLong + coordinate.toLong,
              dimensionPath + s"[$coordinate]"
            )
            coordinate += 1

      if valueType.shape.isEmpty then populateElement(startOffset, path)
      else populateDimensions(0, 0L, "")

    // DONT EVEN USE A ITERATOR JUST USE A FUCKING LOOP OR RECURSIVE FUNCTION CALL PLEASE COCK SUCKER.
    // val fieldDetails = value.fields.iterator.map { case (fieldName, fieldType) =>
    //   val length = byteSize(fieldType)
    //   val field = fieldName -> FieldDetail(nextOffset, length, fieldType)
    //   nextOffset += length
    //   field
    // }.toMap
    measure(this)
    this.total_size = this.byte_size

    var tailValues: Vector[Long] = Vector.empty
    var dimensionIndex = 0
    while dimensionIndex < this.shape.length do
      var dimensionTail = this.element_size
      var followingDimension = dimensionIndex + 1

      while followingDimension < this.shape.length do
        dimensionTail *= this.shape(followingDimension).toLong
        followingDimension += 1

      tailValues = tailValues :+ dimensionTail
      dimensionIndex += 1

    this.tails = tailValues
    populate(this, 0L, "")
    this.index

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


  def tail(dimIndex: Int): Long =
    // Returns the tails of the pushed back memory elements in a specific dimension.
    require(dimIndex >= 0 && dimIndex < this.tails.length, s"Unknown dimension index: $dimIndex")
    this.tails(dimIndex)

  def bytes(valueType: ValueType): Long =
    require(valueType.byte_size > 0, "index_fields() must measure the value type before bytes()")
    valueType.byte_size



// Ideally also a casting mechanism or atleast some operator registry on the base types.

// Be like I can register types programmitaclly, define their behavior, etc etc, automatically cast and do operations on the lowest level base values



class TypeTests:
  def test_type_creation_and_base_functional_iteration_type_and_size_natures(): Unit =
    val positionType = new ValueType(
      "Array",
      Vector(3),
      Map("value" -> "int")
    )

    val particle = new Value(
      "Particle",
      Vector(5),
      Map(
        "id" -> "long",
        "mass" -> "double",
        "position" -> positionType
      )
    )

    particle.index_fields()

    assert(particle.fields("mass").t == "double")
    assert(particle.element_size == 28L)
    assert(particle.total_size == 140L)
    assert(particle.tail(0) == 28L)
    assert(particle.index("[2].mass").offset == 64L)
    assert(particle.index("[2].position[1].value").offset == 76L)
    assert(particle.index("[2].position[1].value").length == 4L)

    particle.allocate()
    assert(particle.memory.length == 140)

    val sameParticle = new Value(
      "Particle",
      Vector(5),
      Map(
        "id" -> "long",
        "mass" -> "double",
        "position" -> new ValueType("Array", Vector(3), Map("value" -> "int"))
      )
    )

    assert(particle == sameParticle)
    assert(particle.hashCode() == sameParticle.hashCode())


// Then when building a type you can like create a base type, and just append shit to the map, and register base type sizes, and or reuse any base operations on the base types.
