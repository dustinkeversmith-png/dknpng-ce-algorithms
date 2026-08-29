// 1. Import the mutable package

package value

import scala.collection.mutable.HashMap
import scala.collection.mutable.LinkedHashMap
import java.nio.ByteBuffer



// A value type description specifying a recursive fields map containing 1 or many nested ValueTypes
// The type_name is a base type defined then recursion breaks when attempting to iterate.
class ValueType(
  var name: String,
  var t: String,
  var shape: Vector[Int],
  var fields: LinkedHashMap[String, ValueType]
):

  var registry: TypeRegistry = new BaseTypes().registerAll()

  def attach_registry(typeRegistry: TypeRegistry): ValueType =
    this.registry = typeRegistry

    val fieldNames = this.fields.keys.toVector
    var fieldIndex = 0
    while fieldIndex < fieldNames.length do
      this.fields(fieldNames(fieldIndex)).attach_registry(typeRegistry)
      fieldIndex += 1

    this

  // Just kind of base line requirements here, hard coded right in. Thats fine I guess..
  require(this.name.nonEmpty, "A value type name cannot be empty")
  require(this.t.nonEmpty, "A canonical value type name cannot be empty")
  require(this.shape.forall(_ > 0), "Shape dimensions must all be positive")

  // Recursive named contents

  // Canonical name for this value type, is registered as a base type if it is a leaf style node such as [int, double] etc or other custom registered types.

  // Shape describes the dimensionality of this value type so it could be like [2][2] meaning it has like a set standard dimensionality of itself 2x2 as a matrix

  // Size of the entire value structure
  var element_size: Long = 0L

  // Size of the entire vector structure.
  var byte_size: Long = 0L


  // Use this fucking constructor right here, so it recurses back down until its not a bean mobile.
  def this(name: String, baseType: String) =
    this(name, baseType, Vector.empty, LinkedHashMap.empty)
    require(baseType.nonEmpty, "A base type name cannot be empty")
   // this name, set, this fields
   // This shit might just actually be something, you mother fucker. If you fuck this up I am going to kill you.

  def this(name: String, fields: scala.collection.Map[String, String | ValueType]) =
    this(name, name, Vector.empty, LinkedHashMap.empty)
    this.parse_fields(fields)

  def this(name: String, shape: Vector[Int], fields: scala.collection.Map[String, String | ValueType]) =
    this(name, name, shape, LinkedHashMap.empty)
    this.parse_fields(fields)

  // This parses a fields input into uniform string to ValueTypes, although I dont like actually having a BaseValueType entire new class for this IMO.
  def parse_fields(fields: scala.collection.Map[String, String | ValueType]): LinkedHashMap[String, ValueType] =
    val parsedFields: LinkedHashMap[String, ValueType] = LinkedHashMap.empty
    val fieldNames = fields.keys.toVector
    var fieldIndex = 0

    while fieldIndex < fieldNames.length do
      val fieldName = fieldNames(fieldIndex)
      val fieldType = fields(fieldName)

      fieldType match
        case baseType: String =>
          parsedFields(fieldName) = new ValueType(fieldName, baseType)
        case valueType: ValueType =>
          parsedFields(fieldName) = valueType

      fieldIndex += 1

    this.fields = parsedFields
    this.fields



  def equals(other: ValueType): Boolean =
        if this.name != other.name then return false
        if this.t != other.t then return false
        if this.shape.length != other.shape.length then return false

        var shapeIndex = 0
        while shapeIndex < this.shape.length do
          if this.shape(shapeIndex) != other.shape(shapeIndex) then return false
          shapeIndex += 1

        if this.fields.size != other.fields.size then return false

        val thisFieldNames = this.fields.keys.toVector.sorted
        val otherFieldNames = other.fields.keys.toVector.sorted
        var fieldIndex = 0

        while fieldIndex < thisFieldNames.length do
          val thisFieldName = thisFieldNames(fieldIndex)
          val otherFieldName = otherFieldNames(fieldIndex)
          if thisFieldName != otherFieldName then return false

          val thisFieldType = this.fields(thisFieldName)
          val otherFieldType = other.fields(otherFieldName)
          if !thisFieldType.equals(otherFieldType) then return false

          fieldIndex += 1

        true

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
  def offset: Long
  def length: Long
  def read(): Array[Byte]
  def write(bytes: Array[Byte]): Unit


// The actual data container for the value type description.
// NO STUPID CONSTRUCTOR CLASSES ONE CLASS ONLY
final class Value(
  valueName: String,
  valueShape: Vector[Int],
  valueFields: scala.collection.Map[String, String | ValueType]
) extends ValueType(valueName, valueShape, valueFields):

  // THIS IS A INDEX NOT DETAILS
  var index: LinkedHashMap[String, FieldIndex] = LinkedHashMap.empty

  /// STANDARD MOTHER FUCKING CONSTRUCTOR

  def this(name: String, valueType: ValueType) =
    this(name, valueType.shape, valueType.fields)
    this.t = valueType.t
    this.attach_registry(valueType.registry)

  require(this.name.nonEmpty, "A value name cannot be empty")
  require(this.shape.forall(_ > 0), "Shape dimensions must all be positive")



  //override val t: String = name

  // Canoncial name of the value itself.


  // NO RUNNING STUPID BEHAVIORS IN THE CLASS BODY ONLY IN THE CLASS FUNCTIONS
  // Memory representing the nested recursive field description, used for subindexing and pased into basetype operators for calculation.
  // private var allocatedMemory: Array[Byte] = Array.emptyByteArray
  // def memory: Array[Byte] = allocatedMemory


  var memory: Array[Byte] = Array.emptyByteArray
  var memory_offset: Long = 0L
  var total_size: Long = 0L
  var tails: Vector[Long] = Vector.empty

  // Value construction now finalizes its direct member layout and owns allocated memory immediately.
  this.index_fields()
  this.allocate()

  override def attach_registry(typeRegistry: TypeRegistry): Value =
    super.attach_registry(typeRegistry)
    this.index_fields()
    this.allocate()
    this

  def register_operator(id: FunctionalId, operatorFunction: OperatorFunction): Unit =
    this.registry.register_operator(this.t, id, operatorFunction)

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
  def index_fields(): LinkedHashMap[String, FieldIndex] =
    // DONT PUT NO EXTRA FUCKING FUNCTION NAMES IF I PUT A FUNCTION NAME DONT GO AROUND IT.

    // RIGHT HERE ALSO ADD UP ALL OF THE SIZES AND SHIT for the Total Size DICK SUCKER
    // AND ALSO YOU HAVE TO POPULATE BASED ON THE SHAPE DID YOU NOT UNDERSTAND THE DIMENSIONS COCK SUCKER?
    // MEASURE ONCE AND POPULATE THE INDEX FOR ALL THE DIMENSION INDEXES TO.

    this.index.clear()

    def measure(valueType: ValueType): Long =
      var measuredElementSize = 0L

      if valueType.fields.isEmpty then
        measuredElementSize = valueType.registry.size(valueType.t)
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

    // DONT EVEN USE A ITERATOR JUST USE A FUCKING LOOP OR RECURSIVE FUNCTION CALL PLEASE COCK SUCKER.
    // val fieldDetails = value.fields.iterator.map { case (fieldName, fieldType) =>
    //   val length = byteSize(fieldType)
    //   val field = fieldName -> FieldDetail(nextOffset, length, fieldType)
    //   nextOffset += length
    //   field
    // }.toMap
    measure(this)
    this.total_size = this.byte_size

    var nextFieldOffset = 0L
    val directFieldNames = this.fields.keys.toVector
    var directFieldIndex = 0
    while directFieldIndex < directFieldNames.length do
      val fieldName = directFieldNames(directFieldIndex)
      val fieldType = this.fields(fieldName)
      this.index(fieldName) = FieldIndex(nextFieldOffset, fieldType.byte_size, fieldType)
      nextFieldOffset += fieldType.byte_size
      directFieldIndex += 1

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

  // Used to define iteration travel in directions, such as next, prev, or arbitrary directions for neighbor hood searches in the dimensionality space
  def index_dimension(indices: Int*): FieldIndex =
    require(indices.length == this.shape.length, s"Expected ${this.shape.length} dimension indices but received ${indices.length}")

    var linearIndex = 0L
    var dimensionIndex = 0
    while dimensionIndex < indices.length do
      val coordinate = indices(dimensionIndex)
      require(
        coordinate >= 0 && coordinate < this.shape(dimensionIndex),
        s"Dimension $dimensionIndex index $coordinate is outside 0 until ${this.shape(dimensionIndex)}"
      )
      linearIndex = linearIndex * this.shape(dimensionIndex).toLong + coordinate.toLong
      dimensionIndex += 1

    FieldIndex(this.memory_offset + linearIndex * this.element_size, this.element_size, this)

  

  def iterate_dimension(indices: Array[Int], offset: Int): Iterator[Value] =
    require(indices.length == this.shape.length, s"Expected ${this.shape.length} dimension indices but received ${indices.length}")
    require(offset >= 0 && offset < this.shape.length, s"Unknown dimension offset: $offset")

    var dimensionIndex = 0
    while dimensionIndex < indices.length do
      require(indices(dimensionIndex) >= 0 && indices(dimensionIndex) < this.shape(dimensionIndex), s"Dimension $dimensionIndex index ${indices(dimensionIndex)} is outside 0 until ${this.shape(dimensionIndex)}")
      dimensionIndex += 1

    val values = Vector.newBuilder[Value]
    var coordinate = indices(offset)
    while coordinate < this.shape(offset) do
      val currentIndices = indices.clone()
      currentIndices(offset) = coordinate
      values += this.reference_element(currentIndices)
      coordinate += 1

    values.result().iterator

  // // Used to aid in iterating through the different sub types such as fields within an actual value.
  // def iterate_value(): Iterator[MemoryRef] =
  //   iterate_dimension().flatMap(_.iterator())

  // Used to aid in iterating through the different sub types such as fields within an actual value.
  def index_value(indices: Seq[Int], fieldNames: String*): Vector[FieldIndex] =
    require(indices.length == this.shape.length, s"Expected ${this.shape.length} dimension indices but received ${indices.length}")

    val element = this.index_dimension(indices*)

    var selectedFields: Vector[FieldIndex] = Vector.empty
    var fieldNameIndex = 0
    while fieldNameIndex < fieldNames.length do
      val fieldName = fieldNames(fieldNameIndex)
      val field = this.index.getOrElse(fieldName, throw new NoSuchElementException(s"Value field has not been indexed: $fieldName"))
      selectedFields = selectedFields :+ FieldIndex(element.offset + field.offset, field.length, field.valueType)
      fieldNameIndex += 1

    selectedFields

  def iterate_value(fieldNames: String*): Iterator[FieldIndex] =
    var selectedFields: Vector[FieldIndex] = Vector.empty
    var elementCount = 1L
    var dimensionIndex = 0
    while dimensionIndex < this.shape.length do
      elementCount *= this.shape(dimensionIndex).toLong
      dimensionIndex += 1

    val selectedFieldNames =
      if fieldNames.nonEmpty then fieldNames.toVector
      else this.fields.keys.toVector

    var linearIndex = 0L
    while linearIndex < elementCount do
      var fieldNameIndex = 0
      while fieldNameIndex < selectedFieldNames.length do
        val fieldName = selectedFieldNames(fieldNameIndex)
        val field = this.index.getOrElse(fieldName, throw new NoSuchElementException(s"Value field has not been indexed: $fieldName"))
        selectedFields = selectedFields :+ FieldIndex(
          this.memory_offset + linearIndex * this.element_size + field.offset,
          field.length,
          field.valueType
        )
        fieldNameIndex += 1
      linearIndex += 1

    selectedFields.iterator

  // So access and iterator can be accessed [1-N][1_N_2] for the initial dimensionality, then it will finally be able to access and iterate each type, by measuring the strides, and recursive strides into the object.



  // Meaning we have ValueType[] -> Represented as the n dimensional version of the shape.


  def tail(dimIndex: Int): Long =
    // Returns the tails of the pushed back memory elements in a specific dimension.
    require(dimIndex >= 0 && dimIndex < this.tails.length, s"Unknown dimension index: $dimIndex")
    this.tails(dimIndex)

  def bytes(valueType: ValueType): Long =
    require(valueType.byte_size > 0, "index_fields() must measure the value type before bytes()")
    valueType.byte_size


  // Returns a Value view backed by this exact same memory array.
  def reference_member(memberName: String): Value =
    this.reference_member(memberName, Array.fill(this.shape.length)(0))

  def reference_member(memberName: String, elementIndex: Array[Int]): Value =
    val field = this.index.getOrElse(memberName, throw new NoSuchElementException(s"Unknown indexed member: $memberName"))
    val elementOffset =
      if this.shape.isEmpty then this.memory_offset
      else this.index_dimension(elementIndex*).offset

    val referencedValue =
      if field.valueType.fields.isEmpty then
        new Value(field.valueType.name, Vector.empty, Map("value" -> field.valueType))
      else
        new Value(field.valueType.name, field.valueType)

    referencedValue.t = field.valueType.t
    referencedValue.registry = field.valueType.registry
    referencedValue.memory = this.memory
    referencedValue.memory_offset = elementOffset + field.offset
    referencedValue.total_size = field.length
    referencedValue.element_size = field.valueType.element_size
    referencedValue.byte_size = field.length
    referencedValue

  // Returns an indexed Value view while keeping the parent memory as the backing storage.
  def reference_element(elementIndex: Array[Int]): Value =
    val element = this.index_dimension(elementIndex*)
    val referencedValue = new Value(this.name, Vector.empty, this.fields)
    referencedValue.t = this.t
    referencedValue.registry = this.registry
    referencedValue.memory = this.memory
    referencedValue.memory_offset = element.offset
    referencedValue.total_size = this.element_size
    referencedValue.element_size = this.element_size
    referencedValue.byte_size = this.element_size

    if referencedValue.fields.size == 1 then
      val elementField = referencedValue.fields(referencedValue.fields.keys.head)
      if elementField.shape.isEmpty && elementField.fields.isEmpty then
        referencedValue.t = elementField.t
        referencedValue.registry = elementField.registry

    referencedValue

  def reference(path: String): Value =
    if this.index.contains(path) then this.reference_member(path)
    else
      val coordinates = """\[(\d+)\]""".r.findAllMatchIn(path).map(_.group(1).toInt).toArray
      if coordinates.nonEmpty then this.reference_element(coordinates)
      else throw new NoSuchElementException(s"Unknown indexed path: $path")

  def base_value(): Value =
    if this.registry.contains(this.t) && this.index.contains("value") then this
    else if this.index.contains("value") && this.index("value").valueType.fields.isEmpty then this.reference("value")
    else if this.fields.size == 1 then this.reference_member(this.fields.keys.head)
    else throw new IllegalArgumentException(s"Value '${this.name}' does not identify one base value")

  def base_type_name(): String =
    val baseValue = this.base_value()
    baseValue.index("value").valueType.t


  def base_paths(): Vector[String] =
    var paths: Vector[String] = Vector.empty
    val fieldNames = this.fields.keys.toVector
    var fieldIndex = 0
    while fieldIndex < fieldNames.length do
      val fieldName = fieldNames(fieldIndex)
      val field = this.index(fieldName)
      if field.valueType.fields.isEmpty && field.valueType.shape.isEmpty then
        paths = paths :+ fieldName
      fieldIndex += 1
    paths

  def iterator(): Iterator[Value] =
    if this.index.isEmpty then this.index_fields()
    if this.memory.isEmpty then this.allocate()

    if this.shape.nonEmpty then
      var values: Vector[Value] = Vector.empty
      var elementCount = 1
      var dimensionIndex = 0
      while dimensionIndex < this.shape.length do
        elementCount *= this.shape(dimensionIndex)
        dimensionIndex += 1

      var linearIndex = 0
      while linearIndex < elementCount do
        val coordinates = Array.ofDim[Int](this.shape.length)
        var remainder = linearIndex
        dimensionIndex = this.shape.length - 1
        while dimensionIndex >= 0 do
          coordinates(dimensionIndex) = remainder % this.shape(dimensionIndex)
          remainder /= this.shape(dimensionIndex)
          dimensionIndex -= 1
        values = values :+ this.reference_element(coordinates)
        linearIndex += 1
      values.iterator
    else
      this.fields.keysIterator
        .filter(fieldName => this.index.contains(fieldName))
        .map(fieldName => this.reference_member(fieldName))

  def value(): Value = this

  def apply(indices: Int*): Value =
    this.reference_element(indices.toArray)

  def apply(memberName: String): Value =
    this.reference_member(memberName)

  def update(index: Int, assignedValue: Value): Unit =
    this.reference_element(Array(index)).operator("=")(assignedValue)

  def update(index: Int, number: Int): Unit =
    val referencedValue = this.reference_element(Array(index))
    referencedValue.operator("=")(referencedValue.registry.caster.cast(referencedValue.base_type_name(), number.toDouble))

  def update(index: Int, number: Double): Unit =
    val referencedValue = this.reference_element(Array(index))
    referencedValue.operator("=")(referencedValue.registry.caster.cast(referencedValue.base_type_name(), number))

  def update(memberName: String, assignedValue: Value): Unit =
    this.reference_member(memberName).operator("=")(assignedValue)

  def update(memberName: String, number: Int): Unit =
    val referencedValue = this.reference_member(memberName)
    referencedValue.operator("=")(referencedValue.registry.caster.cast(referencedValue.base_type_name(), number.toDouble))

  def update(memberName: String, number: Double): Unit =
    val referencedValue = this.reference_member(memberName)
    referencedValue.operator("=")(referencedValue.registry.caster.cast(referencedValue.base_type_name(), number))

  def insert(bytes: Array[Byte]): Value =
    val baseValue = this.base_value()
    val field = baseValue.index("value")
    require(bytes.length == field.length.toInt, s"Expected ${field.length} bytes but received ${bytes.length}")
    Array.copy(bytes, 0, baseValue.memory, (baseValue.memory_offset + field.offset).toInt, bytes.length)
    this

  def operator(name: String)(arguments: Value*): Value =
    var valueArguments: Vector[Value] = Vector.empty
    var argumentIndex = 0

    valueArguments = valueArguments :+ this
    while argumentIndex < arguments.length do
      valueArguments = valueArguments :+ arguments(argumentIndex)
      argumentIndex += 1

    this.registry.operator(name, valueArguments.toArray)

    



// Ideally also a casting mechanism or atleast some operator registry on the base types.

// Be like I can register types programmitaclly, define their behavior, etc etc, automatically cast and do operations on the lowest level base values




// Then when building a type you can like create a base type, and just append shit to the map, and register base type sizes, and or reuse any base operations on the base types.
