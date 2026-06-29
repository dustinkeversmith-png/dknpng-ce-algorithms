package engine.problem

import engine.context.Context
import engine.problem.parsing.{JsonParser, JsonValue, ProblemFormatError, ProblemFormatFunctor}
import engine.problem.parsing.JsonValue.*
import engine.problem.profiles.ProgrammingProblem.JsonProgrammingProblemFunctor

object JsonProblemSpaceFunctor extends ProblemFormatFunctor[String, Vector[Context]]:
  override def map(input: String): Either[ProblemFormatError, Vector[Context]] =
    JsonParser.parseJsonic(input).flatMap {
      case JsonObject(values) =>
        values.get("problems") match
          case Some(JsonArray(items)) => mapItems(items)
          case _ => Left(ProblemFormatError("Expected a problems array"))
      case JsonArray(items) => mapItems(items)
      case _ => Left(ProblemFormatError("Expected a problem-space object or array"))
    }

  private def mapItems(items: Vector[JsonValue]): Either[ProblemFormatError, Vector[Context]] =
    items.foldLeft[Either[ProblemFormatError, Vector[Context]]](Right(Vector.empty)) {
      case (Left(error), _) => Left(error)
      case (Right(contexts), item) =>
        JsonProgrammingProblemFunctor.fromJsonValue(item).map(contexts :+ _)
    }
