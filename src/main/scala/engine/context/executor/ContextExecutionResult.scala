package engine.context.executor

import engine.context.Context
import engine.context.trace.ContextTrace

final case class ContextExecutionResult(
  context: Context,
  trace: ContextTrace,
  success: Boolean,
  error: Option[ContextExecutionError] = None,
  remainingBudget: ContextExecutionBudget
)

object ContextExecutionResult:
  def success(context: Context, trace: ContextTrace, budget: ContextExecutionBudget): ContextExecutionResult =
    ContextExecutionResult(context, trace, success = true, error = None, remainingBudget = budget)

  def failure(context: Context, trace: ContextTrace, error: ContextExecutionError, budget: ContextExecutionBudget): ContextExecutionResult =
    ContextExecutionResult(context, trace, success = false, error = Some(error), remainingBudget = budget)
