trait MemoryRef:
  def valueType: ValueType

  // When reading and writing it will get the offset and size of the value string from the dedicated type.

  def read(value: String): Any
  def write(value: String): Any
