package engine.context.decisions

import engine.context.Context
import engine.context.executor.{ContextCompositionExecutor, ContextExecutionBudget, ContextExecutionError, ContextExecutionResult, ContextStrategyExecutor}
import engine.context.strategy.ContextCompositeStrategy
import engine.context.trace.{ContextTrace, ContextTraceEvent}

/** Executes a choice assembly by delegating child selection to a policy. */
final class ContextChoiceExecutor(selectionPolicy: ContextChoiceSelectionPolicy) extends ContextCompositionExecutor:
  override def execute(
    ContextStrategy: ContextCompositeStrategy,
    context: Context,
    budget: ContextExecutionBudget,
    executor: ContextStrategyExecutor
  ): ContextExecutionResult =
    val baseTrace = ContextTrace.empty.append(ContextTraceEvent.CompositeEntered(ContextStrategy.id, ContextStrategy.compositionMode))

    selectionPolicy.select(ContextStrategy, context, budget) match
      case None =>
        ContextExecutionResult.failure(context, baseTrace, ContextExecutionError.EmptyComposite("Choice has no children"), budget)
      case Some(selected) =>
        val selectedTrace = baseTrace.append(ContextTraceEvent.ChoiceSelected(ContextStrategy.id, selected.id))
        val result = executor.execute(selected, context, budget)
        result.copy(trace = selectedTrace ++ result.trace)

object ContextChoiceExecutor extends ContextCompositionExecutor:
  val default: ContextChoiceExecutor = new ContextChoiceExecutor(ContextChoiceSelectionPolicy.firstChild)

  override def execute(
    ContextStrategy: ContextCompositeStrategy,
    context: Context,
    budget: ContextExecutionBudget,
    executor: ContextStrategyExecutor
  ): ContextExecutionResult =
    default.execute(ContextStrategy, context, budget, executor)
