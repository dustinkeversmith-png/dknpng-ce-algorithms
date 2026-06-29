package engine.solution

final case class SolutionGoalStep(
  index: Int,
  action: SolutionAssembly => Either[String, SolutionDelta],
  description: String
)

final case class SolutionGoalAction(
  name: String,
  run: SolutionAssembly => Either[String, SolutionDelta]
)

final case class SolutionGoal(
  goalType: String,
  action: SolutionGoalAction,
  steps: Vector[SolutionGoalStep] = Vector.empty,
  description: Option[String] = None,
  measurement: Option[SolutionAssembly => SolutionValue] = None
):
  def addStep(step: SolutionGoalStep): SolutionGoal =
    copy(steps = (steps :+ step).sortBy(_.index))
