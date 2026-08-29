package value

import scala.collection.mutable.HashMap

final class Caster(var registry: TypeRegistry):

  var retrieve_functions: HashMap[String, Array[Byte] => Double] = HashMap.empty
  var insert_functions: HashMap[String, Double => Array[Byte]] = HashMap.empty

  def register(name: String, retrieve: Array[Byte] => Double, insert: Double => Array[Byte]): Unit =
    this.retrieve_functions(name) = retrieve
    this.insert_functions(name) = insert

  def retrieve(typeName: String, value: Value): Double =
    val baseValue = value.base_value()
    val field = baseValue.index("value")
    val bytes = Array.ofDim[Byte](field.length.toInt)
    Array.copy(baseValue.memory, (baseValue.memory_offset + field.offset).toInt, bytes, 0, bytes.length)

    this.retrieve_functions.getOrElse(
      typeName,
      throw new NoSuchElementException(s"No retrieval cast is registered for '$typeName'")
    )(bytes)

  def insert(typeName: String, value: Value, number: Double): Value =
    val bytes = this.insert_functions.getOrElse(
      typeName,
      throw new NoSuchElementException(s"No insertion cast is registered for '$typeName'")
    )(number)

    value.insert(bytes)

  def cast(typeName: String, number: Double): Value =
    val result = new Value("result", Vector.empty, Map("value" -> typeName))
    result.attach_registry(this.registry)
    this.insert(typeName, result, number)
