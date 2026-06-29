package engine.context.executor

import engine.context.Context
import engine.context.strategy.ContextCompositeStrategy
import engine.context.trace.{ContextTrace, ContextTraceEvent}

object ContextSequenceExecutor extends ContextCompositionExecutor:
  def execute(
    ContextStrategy: ContextCompositeStrategy,
    context: Context,
    budget: ContextExecutionBudget,
    executor: ContextStrategyExecutor
  ): ContextExecutionResult =
    val startTrace = ContextTrace.empty
      .append(ContextTraceEvent.CompositeEntered(ContextStrategy.id, ContextStrategy.compositionMode))

    ContextStrategy.children.foldLeft(ContextExecutionResult.success(context, startTrace, budget)) { (acc, child) =>
      if !acc.success then acc
      else
        val childResult = executor.execute(child, acc.context, acc.remainingBudget)
        childResult.copy(trace = acc.trace ++ childResult.trace)
    }
