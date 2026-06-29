package engine.context.operation.prefabs

import engine.context.{ContextValue, Context, ContextDelta}
import engine.context.executor.ContextExecutionError
import engine.context.operation.{ContextOperationPlan, ContextOperation}
import engine.context.operation.{ContextAddDeltaStep, ContextReadFactStep}

/**
 * Minimal finite search operation implemented as a small step topology.
 *
 * The public operation remains a normal registry operation, but its internals are now
 * inspectable and extendable as separate steps: require candidates, require target,
 * then resolve the result. Later phases can replace this linear plan with loops or
 * generated subplans without changing the registry contract.
 */
final case class ContextBasicSearchOperation(
  id: String,
  candidatesKey: String,
  targetKey: String,
  resultKey: String
) extends ContextOperation:
  override def description: String = s"Search $candidatesKey for $targetKey"
  val plan: ContextOperationPlan = ContextBasicSearchOperation.plan(id, candidatesKey, targetKey, resultKey)
  override def run(context: Context): Either[ContextExecutionError, ContextDelta] = plan.run(context)

object ContextBasicSearchOperation:
  def plan(id: String, candidatesKey: String, targetKey: String, resultKey: String): ContextOperationPlan =
    ContextOperationPlan(Vector(
      ContextReadFactStep(s"$id.require-candidates", candidatesKey),
      ContextReadFactStep(s"$id.require-target", targetKey),
      ContextAddDeltaStep(
        id = s"$id.resolve-result",
        description = s"Find $targetKey inside $candidatesKey and write $resultKey",
        deltaFactory = context =>
          (context.facts.get(candidatesKey), context.facts.get(targetKey)) match
            case (Some(ContextValue.ContextListValue(candidates, _)), Some(target)) =>
              candidates.find(_ == target) match
                case Some(found) => Right(ContextDelta(addFacts = Map(resultKey -> found)))
                case None => Left(ContextExecutionError.OperationFailed(id, "Target was not found in candidate list"))
            case (None, _) => Left(ContextExecutionError.OperationFailed(id, s"Missing candidates fact: $candidatesKey"))
            case (_, None) => Left(ContextExecutionError.OperationFailed(id, s"Missing target fact: $targetKey"))
            case (Some(other), _) => Left(ContextExecutionError.OperationFailed(id, s"Candidates value must be ContextListValue, got $other"))
      )
    ))
