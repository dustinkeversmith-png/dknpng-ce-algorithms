package engine.problem.parsing

sealed trait JsonValue derives CanEqual
object JsonValue:
  final case class JsonObject(values: Map[String, JsonValue]) extends JsonValue
  final case class JsonArray(values: Vector[JsonValue]) extends JsonValue
  final case class JsonString(value: String) extends JsonValue
  final case class JsonNumber(value: BigDecimal) extends JsonValue
  final case class JsonBoolean(value: Boolean) extends JsonValue
  case object JsonNull extends JsonValue
