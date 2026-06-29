package engine.problem.profiles.ProgrammingProblem

import engine.context.{ContextValue, Context}
import engine.problem.parsing.*
import engine.problem.parsing.JsonValue.*

/**
 * Converts one JSON/JSONic programming problem object into a normalized Context.
 *
 * Supported input shapes:
 *   1. Direct profile JSON:
 *      { "id": "two-sum", "title": "Two Sum", "kind": "algorithm", ... }
 *   2. Existing context JSON:
 *      { "facts": {...}, "unknowns": {...}, "goals": [...], "artifacts": {...} }
 */
object JsonProgrammingProblemFunctor extends ProblemFormatFunctor[String, Context]:
  def map(input: String): Either[ProblemFormatError, Context] =
    JsonParser.parseJsonic(input).flatMap(fromJsonValue)

  def fromJsonValue(json: JsonValue): Either[ProblemFormatError, Context] =
    json match
      case obj @ JsonObject(values) if isContextShape(values) =>
        JsonContextValueCodec.toContext(obj)
      case JsonObject(values) =>
        directProfile(values).map(_.toContext)
      case other =>
        Left(ProblemFormatError("Expected a JSON object representing a programming problem"))

  private def isContextShape(values: Map[String, JsonValue]): Boolean =
    values.contains("facts") || values.contains("unknowns") || values.contains("goals") || values.contains("artifacts")

  private def directProfile(values: Map[String, JsonValue]): Either[ProblemFormatError, ProgrammingProblemProfile] =
    for
      title <- requiredString(values, "title")
      description <- requiredString(values, "description")
      kind <- requiredStringAny(values, Vector("problem.kind", "kind"))
      problemId = optionalStringAny(values, Vector("problem.id", "id")).getOrElse(slug(title))
      domain = optionalStringAny(values, Vector("problem.domain", "domain"))
      inputSpec = fieldAny(values, Vector("input.spec", "inputSpec", "input")).map(JsonContextValueCodec.toContextValue).getOrElse(ContextValue.ContextMapValue(Map.empty))
      outputSpec = fieldAny(values, Vector("output.spec", "outputSpec", "output")).map(JsonContextValueCodec.toContextValue).getOrElse(ContextValue.ContextStringValue("unspecified"))
      constraints <- stringVectorAny(values, Vector("constraints"))
      edgeCases <- stringVectorAny(values, Vector("edgeCases", "edge.cases"))
      examples <- examplesAny(values, Vector("examples"))
      complexityTarget = objectAny(values, Vector("complexity.target", "complexityTarget")).getOrElse(Map.empty).view.mapValues(JsonContextValueCodec.toContextValue).toMap
      languageTarget = optionalStringAny(values, Vector("language.target", "languageTarget"))
      requiredBehavior <- stringVectorAny(values, Vector("requiredBehavior", "required.behavior"))
      allowedTechniques <- stringVectorAny(values, Vector("allowedTechniques", "allowed.techniques"))
      forbiddenTechniques <- stringVectorAny(values, Vector("forbiddenTechniques", "forbidden.techniques"))
      evaluationTests = fieldAny(values, Vector("evaluation.tests", "evaluationTests", "tests")) match
        case Some(JsonArray(items)) => items.map(JsonContextValueCodec.toContextValue)
        case Some(other) => Vector(JsonContextValueCodec.toContextValue(other))
        case None => Vector.empty
      unknowns = objectAny(values, Vector("unknowns")).getOrElse(Map.empty).view.mapValues(JsonContextValueCodec.toContextValue).toMap
      goals <- stringVectorAny(values, Vector("goals"))
      artifacts = objectAny(values, Vector("artifacts")).getOrElse(Map.empty).view.mapValues(JsonContextValueCodec.toContextValue).toMap
      extraFacts = objectAny(values, Vector("facts")).getOrElse(Map.empty).view.mapValues(JsonContextValueCodec.toContextValue).toMap
    yield ProgrammingProblemProfile(
      id = problemId,
      title = title,
      description = description,
      kind = kind,
      domain = domain,
      inputSpec = inputSpec,
      outputSpec = outputSpec,
      constraints = constraints,
      examples = examples,
      edgeCases = edgeCases,
      complexityTarget = complexityTarget,
      languageTarget = languageTarget,
      requiredBehavior = requiredBehavior,
      allowedTechniques = allowedTechniques,
      forbiddenTechniques = forbiddenTechniques,
      evaluationTests = evaluationTests,
      unknowns = unknowns,
      goals = goals,
      artifacts = artifacts,
      extraFacts = extraFacts
    )

  private def requiredString(values: Map[String, JsonValue], key: String): Either[ProblemFormatError, String] =
    values.get(key) match
      case Some(JsonString(value)) => Right(value)
      case Some(_) => Left(ProblemFormatError(s"Expected string field '$key'"))
      case None => Left(ProblemFormatError(s"Missing required field '$key'"))

  private def requiredStringAny(values: Map[String, JsonValue], keys: Vector[String]): Either[ProblemFormatError, String] =
    keys.collectFirst(Function.unlift(key => optionalString(values, key).map(key -> _))) match
      case Some((_, value)) => Right(value)
      case None => Left(ProblemFormatError(s"Missing required string field: one of ${keys.mkString(", ")}"))

  private def optionalString(values: Map[String, JsonValue], key: String): Option[String] =
    values.get(key).collect { case JsonString(value) => value }

  private def optionalStringAny(values: Map[String, JsonValue], keys: Vector[String]): Option[String] =
    keys.collectFirst(Function.unlift(key => optionalString(values, key)))

  private def fieldAny(values: Map[String, JsonValue], keys: Vector[String]): Option[JsonValue] =
    keys.collectFirst(Function.unlift(key => values.get(key)))

  private def objectAny(values: Map[String, JsonValue], keys: Vector[String]): Option[Map[String, JsonValue]] =
    fieldAny(values, keys).collect { case JsonObject(fields) => fields }

  private def stringVectorAny(values: Map[String, JsonValue], keys: Vector[String]): Either[ProblemFormatError, Vector[String]] =
    fieldAny(values, keys) match
      case None => Right(Vector.empty)
      case Some(JsonArray(items)) =>
        val strings = items.collect { case JsonString(value) => value }
        if strings.size == items.size then Right(strings)
        else Left(ProblemFormatError(s"Expected ${keys.mkString("/")} to contain only strings"))
      case Some(JsonString(value)) => Right(Vector(value))
      case Some(_) => Left(ProblemFormatError(s"Expected ${keys.mkString("/")} to be a string or array of strings"))

  private def examplesAny(values: Map[String, JsonValue], keys: Vector[String]): Either[ProblemFormatError, Vector[ProblemExample]] =
    fieldAny(values, keys) match
      case None => Right(Vector.empty)
      case Some(JsonArray(items)) =>
        items.zipWithIndex.foldLeft[Either[ProblemFormatError, Vector[ProblemExample]]](Right(Vector.empty)) {
          case (Left(err), _) => Left(err)
          case (Right(acc), (JsonObject(example), idx)) =>
            val input = example.get("input").map(JsonContextValueCodec.toContextValue).getOrElse(ContextValue.ContextNullValue)
            val output = example.get("output").map(JsonContextValueCodec.toContextValue).getOrElse(ContextValue.ContextNullValue)
            val explanation = example.get("explanation").collect { case JsonString(value) => value }
            Right(acc :+ ProblemExample(input, output, explanation))
          case (Right(_), (_, idx)) => Left(ProblemFormatError(s"Expected example at index $idx to be an object"))
        }
      case Some(_) => Left(ProblemFormatError("Expected examples to be an array"))

  private def slug(value: String): String =
    value.toLowerCase
      .replaceAll("[^a-z0-9]+", "-")
      .replaceAll("(^-|-$)", "")
