package engine.solution

/** Describes a solution value by type, shape, and element structure. */
sealed trait SolutionValue derives CanEqual:
  def description: SolutionValueDescription

final case class SolutionValueDescription(
  valueType: String,
  shape: Option[String] = None,
  elementTypes: Vector[String] = Vector.empty,
  fields: Map[String, String] = Map.empty,
  metadata: Map[String, String] = Map.empty
) derives CanEqual

object SolutionValue:
  final case class SolutionStringValue(value: String, description: SolutionValueDescription = scalar("string")) extends SolutionValue
  final case class SolutionIntValue(value: Int, description: SolutionValueDescription = scalar("integer")) extends SolutionValue
  final case class SolutionDoubleValue(value: Double, description: SolutionValueDescription = scalar("double")) extends SolutionValue
  final case class SolutionBooleanValue(value: Boolean, description: SolutionValueDescription = scalar("boolean")) extends SolutionValue
  final case class SolutionListValue(
    values: Vector[SolutionValue],
    description: SolutionValueDescription = SolutionValueDescription("list", shape = Some("sequence"))
  ) extends SolutionValue
  final case class SolutionMapValue(
    values: Map[String, SolutionValue],
    description: SolutionValueDescription = SolutionValueDescription("map", shape = Some("object"))
  ) extends SolutionValue
  final case class SolutionArtifactValue(
    kind: String,
    values: Map[String, SolutionValue],
    description: SolutionValueDescription = SolutionValueDescription("artifact", shape = Some("artifact"))
  ) extends SolutionValue
  case object SolutionNullValue extends SolutionValue:
    override val description: SolutionValueDescription = scalar("null")

  private def scalar(valueType: String): SolutionValueDescription =
    SolutionValueDescription(valueType)
