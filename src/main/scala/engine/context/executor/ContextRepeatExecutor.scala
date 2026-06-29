package engine.context.executor

import engine.context.Context
import engine.context.strategy.ContextCompositeStrategy
import engine.context.trace.{ContextTrace, ContextTraceEvent}

object ContextRepeatExecutor extends ContextCompositionExecutor:
  def execute(
    ContextStrategy: ContextCompositeStrategy,
    context: Context,
    budget: ContextExecutionBudget,
    executor: ContextStrategyExecutor
  ): ContextExecutionResult =
    val baseTrace = ContextTrace.empty.append(ContextTraceEvent.CompositeEntered(ContextStrategy.id, ContextStrategy.compositionMode))
    val limit = ContextStrategy.repeatLimit.getOrElse(1)

    ContextStrategy.children.headOption match
      case None =>
        ContextExecutionResult.failure(context, baseTrace, ContextExecutionError.EmptyComposite("Repeat has no child"), budget)
      case Some(child) =>
        def loop(iteration: Int, ctx: Context, b: ContextExecutionBudget, trace: ContextTrace): ContextExecutionResult =
          if iteration >= limit then ContextExecutionResult.success(ctx, trace, b)
          else
            val iterationTrace = trace.append(ContextTraceEvent.RepeatIteration(ContextStrategy.id, iteration + 1))
            val result = executor.execute(child, ctx, b)
            val combinedTrace = iterationTrace ++ result.trace
            if result.success then loop(iteration + 1, result.context, result.remainingBudget, combinedTrace)
            else result.copy(trace = combinedTrace)

        loop(0, context, budget, baseTrace)
