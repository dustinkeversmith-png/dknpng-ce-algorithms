package engine.context.executor

import engine.context.Context
import engine.context.strategy.ContextCompositeStrategy
import engine.context.trace.{ContextTrace, ContextTraceEvent}

object ContextMeasureThenSelectExecutor extends ContextCompositionExecutor:
  def execute(
    ContextStrategy: ContextCompositeStrategy,
    context: Context,
    budget: ContextExecutionBudget,
    executor: ContextStrategyExecutor
  ): ContextExecutionResult =
    // Phase 1 placeholder: behave like Choice and select the first child.
    val baseTrace = ContextTrace.empty.append(ContextTraceEvent.CompositeEntered(ContextStrategy.id, ContextStrategy.compositionMode))
    ContextStrategy.children.headOption match
      case None => ContextExecutionResult.failure(context, baseTrace, ContextExecutionError.EmptyComposite("MeasureThenSelect has no children"), budget)
      case Some(selected) =>
        val selectedTrace = baseTrace.append(ContextTraceEvent.ChoiceSelected(ContextStrategy.id, selected.id))
        val result = executor.execute(selected, context, budget)
        result.copy(trace = selectedTrace ++ result.trace)
