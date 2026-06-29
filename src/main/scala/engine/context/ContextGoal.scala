package engine.context

final case class ContextGoalStep(
  index: Int,
  action: Context => Either[String, ContextDelta],
  description: String
) derives CanEqual

final case class ContextGoalAction(
  name: String,
  run: Context => Either[String, ContextDelta]
) derives CanEqual

final case class ContextGoal(
  goalType: String,
  action: ContextGoalAction,
  steps: Vector[ContextGoalStep] = Vector.empty,
  description: Option[String] = None,
  measurement: Option[Context => ContextValue] = None
) derives CanEqual:
  def addStep(step: ContextGoalStep): ContextGoal =
    copy(steps = (steps :+ step).sortBy(_.index))

  def run(context: Context): Either[String, ContextDelta] =
    if steps.isEmpty then action.run(context)
    else
      steps.foldLeft[Either[String, (Context, ContextDelta)]](Right(context -> ContextDelta.empty)) {
        case (Left(error), _) => Left(error)
        case (Right((currentContext, accumulatedDelta)), step) =>
          step.action(currentContext).map { stepDelta =>
            currentContext.applyDelta(stepDelta) -> accumulatedDelta.combine(stepDelta)
          }
      }.map(_._2)
