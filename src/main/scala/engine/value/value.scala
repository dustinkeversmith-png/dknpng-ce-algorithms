// 1. Import the mutable package
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
    this.registry = valueType.registry

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

            this.index(fieldPath) = FieldIndex(nextOffset, fieldType.byte_size, fieldType)

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
            this.index(completePath) = FieldIndex(elementOffset, valueType.element_size, valueType)

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

  // Used to define iteration travel in directions, such as next, prev, or arbitrary directions for neighbor hood searches in the dimensionality space
  def index_dimension(indices: Int*): FieldIndex =
    require(indices.length == this.shape.length, s"Expected ${this.shape.length} dimension indices but received ${indices.length}")

    var dimensionPath = ""
    var dimensionIndex = 0
    while dimensionIndex < indices.length do
      val coordinate = indices(dimensionIndex)
      require(
        coordinate >= 0 && coordinate < this.shape(dimensionIndex),
        s"Dimension $dimensionIndex index $coordinate is outside 0 until ${this.shape(dimensionIndex)}"
      )
      dimensionPath += s"[$coordinate]"
      dimensionIndex += 1

    this.index.getOrElse(
      dimensionPath,
      throw new NoSuchElementException(s"Dimension path has not been indexed: $dimensionPath")
    )

  

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

    var dimensionPath = ""
    var dimensionIndex = 0
    while dimensionIndex < indices.length do
      val coordinate = indices(dimensionIndex)
      require(
        coordinate >= 0 && coordinate < this.shape(dimensionIndex),
        s"Dimension $dimensionIndex index $coordinate is outside 0 until ${this.shape(dimensionIndex)}"
      )
      dimensionPath += s"[$coordinate]"
      dimensionIndex += 1

    var selectedFields: Vector[FieldIndex] = Vector.empty
    var fieldNameIndex = 0
    while fieldNameIndex < fieldNames.length do
      val fieldName = fieldNames(fieldNameIndex)
      val fieldPath =
        if dimensionPath.isEmpty then fieldName
        else s"$dimensionPath.$fieldName"

      selectedFields = selectedFields :+ this.index.getOrElse(
        fieldPath,
        throw new NoSuchElementException(s"Value field has not been indexed: $fieldPath")
      )
      fieldNameIndex += 1

    selectedFields

  def iterate_value(fieldNames: String*): Iterator[FieldIndex] =
    val indexedFields = this.index.toVector
      .filter { case (path, _) => !path.matches("""(\[\d+\])+""") }
      .filter { case (path, _) =>
        if fieldNames.isEmpty then true
        else
          var matches = false
          var fieldNameIndex = 0
          while fieldNameIndex < fieldNames.length && !matches do
            val fieldName = fieldNames(fieldNameIndex)
            matches = path == fieldName || path.endsWith(s".$fieldName")
            fieldNameIndex += 1
          matches
      }
      .sortBy { case (path, fieldIndex) => (fieldIndex.offset, path) }

    indexedFields.map { case (_, fieldIndex) => fieldIndex }.iterator

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
    if this.index.isEmpty then this.index_fields()
    if this.memory.isEmpty then this.allocate()
    this.reference(memberName)

  // Returns an indexed Value view while keeping the parent memory as the backing storage.
  def reference_element(elementIndex: Array[Int]): Value =
    if this.index.isEmpty then this.index_fields()
    if this.memory.isEmpty then this.allocate()

    var directPath = ""
    var dimensionIndex = 0
    while dimensionIndex < elementIndex.length do
      directPath += s"[${elementIndex(dimensionIndex)}]"
      dimensionIndex += 1
    if this.index.contains(directPath) then this.reference(directPath)
    else
      val indexedPaths = this.index.keys.toVector
        .filter(path => path.endsWith(directPath))
        .filter(path => path.matches(""".*\[\d+\]"""))
        .sortBy(_.length)

      if indexedPaths.isEmpty then
        throw new NoSuchElementException(s"Value '${this.name}' has no indexed element $elementIndex")

      this.reference(indexedPaths.head)

  def reference(path: String): Value =
    val field = this.index.getOrElse(path, throw new NoSuchElementException(s"Unknown indexed path: $path"))
    val referencedValue = new Value(field.valueType.name, field.valueType.shape, field.valueType.fields)
    referencedValue.t = field.valueType.t
    referencedValue.registry = field.valueType.registry
    referencedValue.memory = this.memory
    referencedValue.total_size = field.length
    referencedValue.element_size = field.valueType.element_size
    referencedValue.byte_size = field.length
    referencedValue.index.clear()

    if field.valueType.fields.isEmpty && field.valueType.shape.isEmpty then
      referencedValue.shape = Vector.empty
      referencedValue.index("value") = field
    else
      if path.endsWith("]") then
        referencedValue.shape = Vector.empty
        if referencedValue.fields.size == 1 then
          val elementField = referencedValue.fields(referencedValue.fields.keys.head)
          if elementField.shape.isEmpty && elementField.fields.isEmpty then
            referencedValue.t = elementField.t
            referencedValue.registry = elementField.registry

      val indexedPaths = this.index.keys.toVector
      var indexedPathIndex = 0
      while indexedPathIndex < indexedPaths.length do
        val indexedPath = indexedPaths(indexedPathIndex)
        var relativePath = ""

        if indexedPath.startsWith(path + ".") then
          relativePath = indexedPath.substring(path.length + 1)
        else if indexedPath.startsWith(path + "[") then
          relativePath = indexedPath.substring(path.length)

        if relativePath.nonEmpty then
          referencedValue.index(relativePath) = this.index(indexedPath)

        indexedPathIndex += 1

    referencedValue

  def base_value(): Value =
    if this.registry.contains(this.t) && this.index.contains("value") then this
    else if this.index.contains("value") && this.index("value").valueType.fields.isEmpty then this.reference("value")
    else if this.fields.size == 1 then this.reference_member(this.fields.keys.head)
    else throw new IllegalArgumentException(s"Value '${this.name}' does not identify one base value")

  def base_type_name(): String =
    val baseValue = this.base_value()
    baseValue.index("value").valueType.t


  def base_paths(): Vector[String] =
    if this.index.isEmpty then this.index_fields()
    if this.memory.isEmpty then this.allocate()

    this.index.toVector
      .filter { case (_, field) => field.valueType.fields.isEmpty && field.valueType.shape.isEmpty }
      .sortBy { case (path, field) => (field.offset, path) }
      .map { case (path, _) => path }

  def iterator(): Iterator[Value] =
    if this.index.isEmpty then this.index_fields()
    if this.memory.isEmpty then this.allocate()

    if this.shape.nonEmpty then
      this.index.toVector
        .filter { case (path, _) => path.matches("""(\[\d+\])+""") }
        .sortBy { case (path, fieldIndex) => (fieldIndex.offset, path) }
        .map { case (path, _) => this.reference(path) }
        .iterator
    else
      this.fields.keysIterator
        .filter(fieldName => this.index.contains(fieldName))
        .map(fieldName => this.reference_member(fieldName))

  def value(): Value = this

  def insert(bytes: Array[Byte]): Value =
    val baseValue = this.base_value()
    val field = baseValue.index("value")
    require(bytes.length == field.length.toInt, s"Expected ${field.length} bytes but received ${bytes.length}")
    Array.copy(bytes, 0, baseValue.memory, field.offset.toInt, bytes.length)
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
