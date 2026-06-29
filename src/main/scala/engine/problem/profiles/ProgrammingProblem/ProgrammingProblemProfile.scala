package engine.problem.profiles.ProgrammingProblem

import engine.context.{ContextValue, Context}
import engine.problem.parsing.ProblemFormatError

/** Standard vocabulary for programming-problem contexts. */
object ProgrammingProblemKeys:
  val Profile = "profile"
  val Id = "problem.id"
  val Kind = "problem.kind"
  val Domain = "problem.domain"
  val Title = "title"
  val Description = "description"
  val InputSpec = "input.spec"
  val OutputSpec = "output.spec"
  val Constraints = "constraints"
  val Examples = "examples"
  val EdgeCases = "edgeCases"
  val ComplexityTarget = "complexity.target"
  val LanguageTarget = "language.target"
  val RequiredBehavior = "requiredBehavior"
  val AllowedTechniques = "allowedTechniques"
  val ForbiddenTechniques = "forbiddenTechniques"
  val EvaluationTests = "evaluation.tests"
  val SolutionAlgorithm = "solution.algorithm"
  val SolutionCode = "solution.code"
  val CorrectnessProof = "proof.correctness"
  val MissingEdgeCases = "missing.edgeCases"

final case class ProblemExample(
  input: ContextValue,
  output: ContextValue,
  explanation: Option[String] = None
):
  def toContextValue: ContextValue =
    val base = Map(
      "input" -> input,
      "output" -> output
    )
    ContextValue.ContextMapValue(explanation.fold(base)(value => base + ("explanation" -> ContextValue.ContextStringValue(value))))


// So for something like this in the "problem space", we basically just need registries or adaptations of the base goals, and other context
// description which are specific to the problem.



/**
 * Typed programming-problem profile layered on top of Context.
 *
 * This keeps the ContextStrategy engine generic while giving programming problems a stable
 * vocabulary for algorithms, data-structures, parsers, refactors, systems tasks, etc.
 */
final case class ProgrammingProblemProfile(
  id: String,
  title: String,
  description: String,
  kind: String,
  domain: Option[String] = None, // This domain may represent the domain of programming its in, but might just be empty??
  inputSpec: ContextValue = ContextValue.ContextMapValue(Map.empty),
  outputSpec: ContextValue = ContextValue.ContextStringValue("unspecified"),
  constraints: Vector[String] = Vector.empty,
  examples: Vector[ProblemExample] = Vector.empty,
  edgeCases: Vector[String] = Vector.empty, // We are going to need objective objects representing all of these unknown variables for example,
  // The object would have a evaluator, a description, some kind of way to map to relevant facts in the process, etc. Same for other things
  complexityTarget: Map[String, ContextValue] = Map.empty,
  languageTarget: Option[String] = None, // language target should be removed
  requiredBehavior: Vector[String] = Vector.empty,
  allowedTechniques: Vector[String] = Vector.empty,
  forbiddenTechniques: Vector[String] = Vector.empty,
  evaluationTests: Vector[ContextValue] = Vector.empty,
  unknowns: Map[String, ContextValue] = Map.empty,
  goals: Vector[String] = Vector.empty,
  artifacts: Map[String, ContextValue] = Map.empty,
  extraFacts: Map[String, ContextValue] = Map.empty
):
  import ProgrammingProblemKeys.*

  def toContext: Context =
    val canonicalFacts = Map(
      Profile -> ContextValue.ContextStringValue("programming-problem/v1"),
      Id -> ContextValue.ContextStringValue(id),
      Kind -> ContextValue.ContextStringValue(kind),
      Title -> ContextValue.ContextStringValue(title),
      Description -> ContextValue.ContextStringValue(description),
      InputSpec -> inputSpec,
      OutputSpec -> outputSpec,
      Constraints -> ContextValue.ContextListValue(constraints.map(ContextValue.ContextStringValue(_))),
      Examples -> ContextValue.ContextListValue(examples.map(_.toContextValue)),
      EdgeCases -> ContextValue.ContextListValue(edgeCases.map(ContextValue.ContextStringValue(_))),
      ComplexityTarget -> ContextValue.ContextMapValue(complexityTarget),
      RequiredBehavior -> ContextValue.ContextListValue(requiredBehavior.map(ContextValue.ContextStringValue(_))),
      AllowedTechniques -> ContextValue.ContextListValue(allowedTechniques.map(ContextValue.ContextStringValue(_))),
      ForbiddenTechniques -> ContextValue.ContextListValue(forbiddenTechniques.map(ContextValue.ContextStringValue(_))),
      EvaluationTests -> ContextValue.ContextListValue(evaluationTests)
    ) ++ domain.map(value => Domain -> ContextValue.ContextStringValue(value))
      ++ languageTarget.map(value => LanguageTarget -> ContextValue.ContextStringValue(value))

    val canonicalUnknowns = Map(
      SolutionAlgorithm -> ContextValue.ContextStringValue("Unknown"),
      SolutionCode -> ContextValue.ContextStringValue("Unknown"),
      CorrectnessProof -> ContextValue.ContextStringValue("Unknown"),
      MissingEdgeCases -> ContextValue.ContextStringValue("Unknown")
    ) ++ unknowns

    val canonicalGoals =
      if goals.nonEmpty then goals
      else Vector(
        "understand input and output specification",
        "derive solution ContextStrategy",
        "generate implementation",
        "generate tests",
        "validate constraints and edge cases"
      )

    Context(
      facts = canonicalFacts ++ extraFacts,
      unknowns = canonicalUnknowns,
      goals = canonicalGoals,
      artifacts = artifacts
    )

object ProgrammingProblemProfile:
  def fromContext(context: Context): Either[ProblemFormatError, ProgrammingProblemProfile] =
    def stringFact(key: String): Option[String] = context.facts.get(key).flatMap(_.asStringOption)
    def stringListFact(key: String): Vector[String] =
      context.facts.get(key).flatMap(_.asVectorOption).toVector.flatten.flatMap(_.asStringOption)

    val id = stringFact(ProgrammingProblemKeys.Id).orElse(stringFact("id"))
    val title = stringFact(ProgrammingProblemKeys.Title)
    val description = stringFact(ProgrammingProblemKeys.Description)
    val kind = stringFact(ProgrammingProblemKeys.Kind)

    (id, title, description, kind) match
      case (Some(problemId), Some(problemTitle), Some(problemDescription), Some(problemKind)) =>
        Right(
          ProgrammingProblemProfile(
            id = problemId,
            title = problemTitle,
            description = problemDescription,
            kind = problemKind,
            domain = stringFact(ProgrammingProblemKeys.Domain),
            inputSpec = context.facts.getOrElse(ProgrammingProblemKeys.InputSpec, ContextValue.ContextMapValue(Map.empty)),
            outputSpec = context.facts.getOrElse(ProgrammingProblemKeys.OutputSpec, ContextValue.ContextStringValue("unspecified")),
            constraints = stringListFact(ProgrammingProblemKeys.Constraints),
            edgeCases = stringListFact(ProgrammingProblemKeys.EdgeCases),
            languageTarget = stringFact(ProgrammingProblemKeys.LanguageTarget),
            requiredBehavior = stringListFact(ProgrammingProblemKeys.RequiredBehavior),
            allowedTechniques = stringListFact(ProgrammingProblemKeys.AllowedTechniques),
            forbiddenTechniques = stringListFact(ProgrammingProblemKeys.ForbiddenTechniques),
            unknowns = context.unknowns,
            goals = context.goals,
            artifacts = context.artifacts,
            extraFacts = context.facts -- Set(
              ProgrammingProblemKeys.Profile,
              ProgrammingProblemKeys.Id,
              ProgrammingProblemKeys.Kind,
              ProgrammingProblemKeys.Domain,
              ProgrammingProblemKeys.Title,
              ProgrammingProblemKeys.Description,
              ProgrammingProblemKeys.InputSpec,
              ProgrammingProblemKeys.OutputSpec,
              ProgrammingProblemKeys.Constraints,
              ProgrammingProblemKeys.Examples,
              ProgrammingProblemKeys.EdgeCases,
              ProgrammingProblemKeys.ComplexityTarget,
              ProgrammingProblemKeys.LanguageTarget,
              ProgrammingProblemKeys.RequiredBehavior,
              ProgrammingProblemKeys.AllowedTechniques,
              ProgrammingProblemKeys.ForbiddenTechniques,
              ProgrammingProblemKeys.EvaluationTests
            )
          )
        )
      case _ =>
        Left(ProblemFormatError("Context is missing required programming profile facts: problem.id/id, title, description, problem.kind"))
