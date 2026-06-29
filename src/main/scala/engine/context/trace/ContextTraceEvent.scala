package engine.context.trace

import engine.context.ContextDelta
import engine.context.strategy.{ContextCompositionMode, ContextStrategyId}

sealed trait ContextTraceEvent:
  def ContextStrategyId: ContextStrategyId

object ContextTraceEvent:
  final case class StrategyStarted(ContextStrategyId: ContextStrategyId, name: String) extends ContextTraceEvent
  final case class StrategySucceeded(ContextStrategyId: ContextStrategyId) extends ContextTraceEvent
  final case class StrategyFailed(ContextStrategyId: ContextStrategyId, reason: String) extends ContextTraceEvent
  final case class DeltaApplied(ContextStrategyId: ContextStrategyId, delta: ContextDelta) extends ContextTraceEvent
  final case class BudgetConsumed(ContextStrategyId: ContextStrategyId, remainingSteps: Int, currentDepth: Int) extends ContextTraceEvent
  final case class CompositeEntered(ContextStrategyId: ContextStrategyId, mode: ContextCompositionMode) extends ContextTraceEvent
  final case class ChoiceSelected(ContextStrategyId: ContextStrategyId, selectedChildId: ContextStrategyId) extends ContextTraceEvent
  final case class RepeatIteration(ContextStrategyId: ContextStrategyId, iteration: Int) extends ContextTraceEvent
