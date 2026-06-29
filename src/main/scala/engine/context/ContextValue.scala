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

final case class ContextValueDescription(
  valueType: String,
  shape: Option[String] = None,
  elementTypes: Vector[String] = Vector.empty,
  fields: Map[String, String] = Map.empty,
  metadata: Map[String, String] = Map.empty
) derives CanEqual

object ContextValue:
  final case class ContextStringValue(value: String, description: ContextValueDescription = scalar("string")) extends ContextValue
  final case class ContextIntValue(value: Int, description: ContextValueDescription = scalar("integer")) extends ContextValue
  final case class ContextDoubleValue(value: Double, description: ContextValueDescription = scalar("double")) extends ContextValue
  final case class ContextBooleanValue(value: Boolean, description: ContextValueDescription = scalar("boolean")) extends ContextValue
  final case class ContextListValue(
    values: Vector[ContextValue],
    description: ContextValueDescription = ContextValueDescription("list", shape = Some("sequence"))
  ) extends ContextValue
  final case class ContextMapValue(
    values: Map[String, ContextValue],
    description: ContextValueDescription = ContextValueDescription("map", shape = Some("object"))
  ) extends ContextValue
  final case class ContextArtifactValue(
    kind: String,
    values: Map[String, ContextValue],
    description: ContextValueDescription = ContextValueDescription("artifact", shape = Some("artifact"))
  ) extends ContextValue
  case object ContextNullValue extends ContextValue:
    override val description: ContextValueDescription = scalar("null")

  def string(value: String): ContextValue = ContextStringValue(value)
  def int(value: Int): ContextValue = ContextIntValue(value)
  def bool(value: Boolean): ContextValue = ContextBooleanValue(value)
  def list(values: String*): ContextValue = ContextListValue(values.toVector.map(ContextStringValue(_)))

  def describedList(values: Vector[ContextValue]): ContextValue =
    ContextListValue(values, ContextValueDescription("list", shape = Some(s"size:${values.size}"), elementTypes = values.map(_.description.valueType).distinct))

  def describedMap(values: Map[String, ContextValue]): ContextValue =
    ContextMapValue(values, ContextValueDescription("map", shape = Some("object"), fields = values.view.mapValues(_.description.valueType).toMap))

  private def scalar(valueType: String): ContextValueDescription =
    ContextValueDescription(valueType)
