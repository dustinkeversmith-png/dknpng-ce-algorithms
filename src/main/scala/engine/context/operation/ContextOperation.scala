package engine.context.operation

import engine.context.{Context, ContextDelta}
import engine.context.executor.ContextExecutionError

/**
 * A context operation mutates or enriches the Context only.
 *
 * Solution-level operations are modeled separately under engine.solution so the
 * engine can distinguish context-description changes from generated solution
 * topology.
 */
trait ContextOperation:
  def id: String
  def description: String = id
  def steps: Vector[ContextOperationStep] = Vector.empty
  def run(context: Context): Either[ContextExecutionError, ContextDelta]

final case class ContextOperationPlan(steps: Vector[ContextOperationStep]):
  def run(context: Context): Either[ContextExecutionError, ContextDelta] =
    steps.foldLeft[Either[ContextExecutionError, (Context, ContextDelta)]](Right(context -> ContextDelta.empty)) {
      case (Left(error), _) => Left(error)
      case (Right((currentContext, accumulatedDelta)), step) =>
        step.run(currentContext).map { stepDelta =>
          val nextDelta = accumulatedDelta.combine(stepDelta)
          currentContext.applyDelta(stepDelta) -> nextDelta
        }
    }.map(_._2)
