package engine.context.executor

import engine.context.Context
import engine.context.strategy.ContextCompositeStrategy
import engine.context.trace.{ContextTrace, ContextTraceEvent}
import engine.context.strategy.ContextStrategy

object ContextFallbackExecutor extends ContextCompositionExecutor:
  def execute(
    ContextStrategy: ContextCompositeStrategy,
    context: Context,
    budget: ContextExecutionBudget,
    executor: ContextStrategyExecutor
  ): ContextExecutionResult =
    val baseTrace = ContextTrace.empty.append(ContextTraceEvent.CompositeEntered(ContextStrategy.id, ContextStrategy.compositionMode))

    if ContextStrategy.children.isEmpty then
      ContextExecutionResult.failure(context, baseTrace, ContextExecutionError.EmptyComposite("Fallback has no children"), budget)
    else
      var trace = baseTrace
      var currentBudget = budget
      var lastError: Option[ContextExecutionError] = None

      ContextStrategy.children.foreach { child =>
        if lastError.isDefined || trace.nonEmpty then ()
      }

      def loop(children: Vector[ContextStrategy], b: ContextExecutionBudget, t: ContextTrace): ContextExecutionResult =
        children.headOption match
          case None =>
            ContextExecutionResult.failure(context, t, lastError.getOrElse(ContextExecutionError.EmptyComposite("All fallback children failed")), b)
          case Some(child) =>
            val result = executor.execute(child, context, b)
            val combinedTrace = t ++ result.trace
            if result.success then result.copy(trace = combinedTrace)
            else
              lastError = result.error
              loop(children.tail, result.remainingBudget, combinedTrace)

      loop(ContextStrategy.children, currentBudget, trace)
