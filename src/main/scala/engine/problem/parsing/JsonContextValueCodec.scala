package engine.problem.parsing

import engine.context.{ContextValue, Context}
import JsonValue.*


/** Codec facade for converting parsed JSON values into engine ContextValue/Context values. */
object JsonContextValueCodec:
  def toContextValue(json: JsonValue): ContextValue =
    json match
      case JsonString(value) => ContextValue.ContextStringValue(value)
      case JsonNumber(value) if value.isValidInt => ContextValue.ContextIntValue(value.toInt)
      case JsonNumber(value) => ContextValue.ContextDoubleValue(value.toDouble)
      case JsonBoolean(value) => ContextValue.ContextBooleanValue(value)
      case JsonNull => ContextValue.ContextNullValue
      case JsonArray(values) => ContextValue.ContextListValue(values.map(toContextValue))
      case JsonObject(values) =>
        decodeTaggedContextValue(values).getOrElse(ContextValue.ContextMapValue(values.view.mapValues(toContextValue).toMap))

  def toContext(json: JsonValue): Either[ProblemFormatError, Context] =
    json match
      case JsonObject(values) if values.contains("facts") || values.contains("unknowns") || values.contains("goals") || values.contains("artifacts") =>
        for
          facts <- objectField(values, "facts").map(_.view.mapValues(toContextValue).toMap)
          unknowns <- objectField(values, "unknowns").map(_.view.mapValues(toContextValue).toMap)
          goals <- stringVectorField(values, "goals")
          artifacts <- objectField(values, "artifacts").map(_.view.mapValues(toContextValue).toMap)
        yield Context(facts, unknowns, goals, artifacts)
      case other => Left(ProblemFormatError("Expected a Context JSON object with facts/unknowns/goals/artifacts"))

  private def decodeTaggedContextValue(values: Map[String, JsonValue]): Option[ContextValue] =
    if values.size != 1 then None
    else values.head match
      case ("ContextStringValue", JsonString(value)) => Some(ContextValue.ContextStringValue(value))
      case ("ContextIntValue", JsonNumber(value)) if value.isValidInt => Some(ContextValue.ContextIntValue(value.toInt))
      case ("ContextDoubleValue", JsonNumber(value)) => Some(ContextValue.ContextDoubleValue(value.toDouble))
      case ("ContextBooleanValue", JsonBoolean(value)) => Some(ContextValue.ContextBooleanValue(value))
      case ("ContextListValue", JsonArray(items)) => Some(ContextValue.ContextListValue(items.map(toContextValue)))
      case ("ContextMapValue", JsonObject(items)) => Some(ContextValue.ContextMapValue(items.view.mapValues(toContextValue).toMap))
      case ("ContextArtifactValue", JsonObject(items)) =>
        val kind = items.get("kind").collect { case JsonString(value) => value }
        val artifactValues = items.get("values").collect { case JsonObject(v) => v.view.mapValues(toContextValue).toMap }
        for k <- kind; v <- artifactValues yield ContextValue.ContextArtifactValue(k, v)
      case ("ContextNullValue", _) => Some(ContextValue.ContextNullValue)
      case _ => None

  private def objectField(values: Map[String, JsonValue], key: String): Either[ProblemFormatError, Map[String, JsonValue]] =
    values.get(key) match
      case None => Right(Map.empty)
      case Some(JsonObject(fields)) => Right(fields)
      case Some(_) => Left(ProblemFormatError(s"Expected object field '$key'"))

  private def stringVectorField(values: Map[String, JsonValue], key: String): Either[ProblemFormatError, Vector[String]] =
    values.get(key) match
      case None => Right(Vector.empty)
      case Some(JsonArray(items)) =>
        val strings = items.collect { case JsonString(value) => value }
        if strings.size == items.size then Right(strings)
        else Left(ProblemFormatError(s"Expected '$key' to contain only strings"))
      case Some(_) => Left(ProblemFormatError(s"Expected array field '$key'"))
