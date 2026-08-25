package engine.context

/** Describes a context value by type, shape, and element structure, not by hard-coded sample values. */
sealed trait ContextValue derives CanEqual:
  def description: ContextValueDescription

  def asStringOption: Option[String] = this match
    case ContextValue.ContextStringValue(value, _) => Some(value)
    case _ => None

  def asVectorOption: Option[Vector[ContextValue]] = this match
    case ContextValue.ContextListValue(values, _) => Some(values)
    case _ => None

final case class ValueDescription(
  valueType: String,
  shape: Option[String] = None,
  elementTypes: Vector[String] = Vector.empty,
  fields: Map[String, String] = Map.empty,
  metadata: Map[String, String] = Map.empty
) derives CanEqual

object Value:
  final case class ContextStringValue(value: String, description: ValueDescription = scalar("string")) extends Value
  final case class ContextIntValue(value: Int, description: ValueDescription = scalar("integer")) extends Value
  final case class ContextDoubleValue(value: Double, description: ValueDescription = scalar("double")) extends Value
  final case class ContextBooleanValue(value: Boolean, description: ValueDescription = scalar("boolean")) extends Value
  final case class ContextListValue(
    values: Vector[Value],
    description: ValueDescription = ValueDescription("list", shape = Some("sequence"))
  ) extends Value
  final case class ContextMapValue(
    values: Map[String, Value],
    description: ValueDescription = ValueDescription("map", shape = Some("object"))
  ) extends Value
  final case class ContextArtifactValue(
    kind: String,
    values: Map[String, Value],
    description: ValueDescription = ValueDescription("artifact", shape = Some("artifact"))
  ) extends Value
  case object ContextNullValue extends Value:
    override val description: ValueDescription = scalar("null")

  def string(value: String): Value = ContextStringValue(value)
  def int(value: Int): Value = ContextIntValue(value)
  def bool(value: Boolean): Value = ContextBooleanValue(value)
  def list(values: String*): Value = ContextListValue(values.toVector.map(ContextStringValue(_)))

  def describedList(values: Vector[Value]): Value =
    ContextListValue(values, ValueDescription("list", shape = Some(s"size:${values.size}"), elementTypes = values.map(_.description.valueType).distinct))

  def describedMap(values: Map[String, Value]): Value =
    ContextMapValue(values, ValueDescription("map", shape = Some("object"), fields = values.view.mapValues(_.description.valueType).toMap))

  private def scalar(valueType: String): ValueDescription =
    ValueDescription(valueType)
