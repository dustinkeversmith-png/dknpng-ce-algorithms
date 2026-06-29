package engine.context.executor
import engine.context.executor.ContextExecutionError

final case class ContextExecutionBudget(
  maxSteps: Int,
  maxDepth: Int,
  currentDepth: Int = 0
):
  def remainingSteps: Int = maxSteps

  def consumeStep: Either[ContextExecutionError, ContextExecutionBudget] =
    if maxSteps <= 0 then Left(ContextExecutionError.BudgetExceeded("No execution steps remaining"))
    else Right(copy(maxSteps = maxSteps - 1))

  def enterDepth: Either[ContextExecutionError, ContextExecutionBudget] =
    if currentDepth + 1 > maxDepth then
      Left(ContextExecutionError.DepthExceeded(s"Max execution depth exceeded: $maxDepth"))
    else
      Right(copy(currentDepth = currentDepth + 1))

  def exitDepth: ContextExecutionBudget =
    copy(currentDepth = math.max(0, currentDepth - 1))
