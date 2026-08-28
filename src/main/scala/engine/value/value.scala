// 1. Import the mutable package
import scala.collection.mutable.HashMap
import java.nio.ByteBuffer



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
    require(baseType.nonEmpty, "A base type name cannot be empty")
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

  // Every Value carries the registry which measures and operates on its base leaves.
  var registry: TypeRegistry = TypeRegistry.default

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
        measuredElementSize = this.registry.size(valueType.t)
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

  def iterate_dimension(): Iterator[FieldIndex] =
    this.index.toVector
      .filter { case (path, _) => path.matches("""(\[\d+\])+""") }
      .sortBy { case (path, fieldIndex) => (fieldIndex.offset, path) }
      .map { case (_, fieldIndex) => fieldIndex }
      .iterator

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
  def apply(memberName: String): Value =
    if this.index.isEmpty then this.index_fields()
    if this.memory.isEmpty then this.allocate()
    this.reference(memberName)

  // Returns an indexed Value view while keeping the parent memory as the backing storage.
  def apply(elementIndex: Int): Value =
    if this.index.isEmpty then this.index_fields()
    if this.memory.isEmpty then this.allocate()

    val directPath = s"[$elementIndex]"
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
    referencedValue.registry = this.registry
    referencedValue.memory = this.memory
    referencedValue.total_size = field.length
    referencedValue.element_size = field.valueType.element_size
    referencedValue.byte_size = field.length
    referencedValue.index = Map.empty

    if field.valueType.fields.isEmpty && field.valueType.shape.isEmpty then
      referencedValue.shape = Vector.empty
      referencedValue.index = Map("value" -> field)
    else
      if path.endsWith("]") then referencedValue.shape = Vector.empty

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
          referencedValue.index = referencedValue.index.updated(relativePath, this.index(indexedPath))

        indexedPathIndex += 1

    referencedValue

  def base_value(): Value =
    if this.registry.contains(this.t) && this.index.contains("value") then this
    else if this.index.contains("value") && this.index("value").valueType.fields.isEmpty then this.reference("value")
    else if this.fields.size == 1 then this(this.fields.keys.head)
    else throw new IllegalArgumentException(s"Value '${this.name}' does not identify one base value")

  def base_type_name(): String =
    val baseValue = this.base_value()
    baseValue.index("value").valueType.t

  def number(): Double =
    val baseValue = this.base_value()
    val field = baseValue.index("value")
    val memoryBuffer = ByteBuffer.wrap(baseValue.memory)
    memoryBuffer.position(field.offset.toInt)

    field.valueType.t match
      case "byte" => memoryBuffer.get().toDouble
      case "short" => memoryBuffer.getShort().toDouble
      case "int" => memoryBuffer.getInt().toDouble
      case "long" => memoryBuffer.getLong().toDouble
      case "float" => memoryBuffer.getFloat().toDouble
      case "double" => memoryBuffer.getDouble()
      case typeName => throw new IllegalArgumentException(s"Base type '$typeName' does not define numeric storage")

  def set_number(number: Double): Value =
    val baseValue = this.base_value()
    val field = baseValue.index("value")
    val memoryBuffer = ByteBuffer.wrap(baseValue.memory)
    memoryBuffer.position(field.offset.toInt)

    field.valueType.t match
      case "byte" => memoryBuffer.put(number.toByte)
      case "short" => memoryBuffer.putShort(number.toShort)
      case "int" => memoryBuffer.putInt(number.toInt)
      case "long" => memoryBuffer.putLong(number.toLong)
      case "float" => memoryBuffer.putFloat(number.toFloat)
      case "double" => memoryBuffer.putDouble(number)
      case typeName => throw new IllegalArgumentException(s"Base type '$typeName' does not define numeric storage")

    this

  def truth(): Boolean = this.number() != 0.0

  def integer(): Int = this.number().toInt

  def assign(other: Value): Value =
    this.set_number(other.number())
    this

  def numeric_result(other: Value, operation: (Double, Double) => Double): Value =
    val result = this.registry.value("result", this.base_type_name())
    result.set_number(operation(this.number(), other.number()))
    result

  def comparison_result(other: Value, operation: (Double, Double) => Boolean): Value =
    val result = this.registry.value("comparison", "byte")
    result.set_number(if operation(this.number(), other.number()) then 1.0 else 0.0)
    result

  def boolean_result(other: Value, operation: (Boolean, Boolean) => Boolean): Value =
    val result = this.registry.value("logical", "byte")
    result.set_number(if operation(this.truth(), other.truth()) then 1.0 else 0.0)
    result

  def base_paths(): Vector[String] =
    if this.index.isEmpty then this.index_fields()
    if this.memory.isEmpty then this.allocate()

    this.index.toVector
      .filter { case (_, field) => field.valueType.fields.isEmpty && field.valueType.shape.isEmpty }
      .sortBy { case (path, field) => (field.offset, path) }
      .map { case (path, _) => path }

  def operators(name: String)(arguments: (Value | Double | Float | Long | Int | Short | Byte | Boolean)*): Value =
    var valueArguments: Vector[Value] = Vector.empty
    var argumentIndex = 0

    while argumentIndex < arguments.length do
      val argument = arguments(argumentIndex)
      val valueArgument = argument match
        case value: Value => value
        case number: Double => this.registry.literal(number)
        case number: Float => this.registry.literal(number)
        case number: Long => this.registry.literal(number)
        case number: Int => this.registry.literal(number)
        case number: Short => this.registry.literal(number)
        case number: Byte => this.registry.literal(number)
        case boolean: Boolean => this.registry.literal(boolean)

      valueArguments = valueArguments :+ valueArgument
      argumentIndex += 1

    val thisBasePaths = this.base_paths()

    if thisBasePaths.length == 1 then
      val baseValue = this.reference(thisBasePaths.head)
      val baseType = baseValue.base_type_name()
      val baseOperators = this.registry.operators.getOrElse(
        baseType,
        throw new NoSuchElementException(s"No operators are registered for base type '$baseType'")
      )
      baseOperators.operator(name, baseValue, valueArguments)
    else
      require(valueArguments.length <= 1, s"Recursive operator '$name' accepts at most one Value argument")

      val comparisonOperators = Set("<", "<=", ">", ">=", "==", "!=", "equals", "&&", "||")
      val mutatingOperators = Set("=", "+=", "-=", "*=", "/=", "%=")
      val rightValue = valueArguments.headOption
      val rightBasePaths = rightValue.map(_.base_paths()).getOrElse(Vector.empty)

      if comparisonOperators.contains(name) then
        var comparison = if name == "||" then false else true
        var pathIndex = 0

        while pathIndex < thisBasePaths.length do
          val leftLeaf = this.reference(thisBasePaths(pathIndex))
          val rightLeaf = rightValue match
            case Some(value) =>
              val rightPath = if rightBasePaths.length == 1 then rightBasePaths.head else rightBasePaths(pathIndex)
              value.reference(rightPath)
            case None => leftLeaf
          val leafResult = leftLeaf.operators(name)(rightLeaf).truth()

          if name == "||" then comparison = comparison || leafResult
          else comparison = comparison && leafResult
          pathIndex += 1

        this.registry.literal(comparison)
      else
        val result =
          if mutatingOperators.contains(name) then this
          else
            val createdResult = new Value(s"${this.name}_result", this.shape, this.fields)
            createdResult.registry = this.registry
            createdResult.index_fields()
            createdResult.allocate()
            createdResult

        val resultBasePaths = result.base_paths()
        var pathIndex = 0

        while pathIndex < thisBasePaths.length do
          val leftLeaf = this.reference(thisBasePaths(pathIndex))
          val rightLeaf = rightValue match
            case Some(value) =>
              val rightPath = if rightBasePaths.length == 1 then rightBasePaths.head else rightBasePaths(pathIndex)
              value.reference(rightPath)
            case None => leftLeaf
          val leafResult = leftLeaf.operators(name)(rightLeaf)

          if !mutatingOperators.contains(name) then
            result.reference(resultBasePaths(pathIndex)).operators("=")(leafResult)

          pathIndex += 1

        result



// Ideally also a casting mechanism or atleast some operator registry on the base types.

// Be like I can register types programmitaclly, define their behavior, etc etc, automatically cast and do operations on the lowest level base values




// Then when building a type you can like create a base type, and just append shit to the map, and register base type sizes, and or reuse any base operations on the base types.
