package engine.context.registry

import engine.context.strategy.ContextCompositionMode
import engine.context.executor.*
import engine.context.decisions.ContextChoiceExecutor

/** Registry that decouples the interpreter from concrete composition executors. */
final case class ContextCompositionExecutorRegistry(executors: Map[ContextCompositionMode, ContextCompositionExecutor]):
  def get(mode: ContextCompositionMode): Option[ContextCompositionExecutor] = executors.get(mode)
  def register(mode: ContextCompositionMode, executor: ContextCompositionExecutor): ContextCompositionExecutorRegistry =
    copy(executors = executors + (mode -> executor))

object ContextCompositionExecutorRegistry:
  val default: ContextCompositionExecutorRegistry = ContextCompositionExecutorRegistry(Map(
    ContextCompositionMode.Sequence -> ContextSequenceExecutor,
    ContextCompositionMode.Fallback -> ContextFallbackExecutor,
    ContextCompositionMode.Choice -> ContextChoiceExecutor,
    ContextCompositionMode.Repeat -> ContextRepeatExecutor,
    ContextCompositionMode.MeasureThenSelect -> ContextMeasureThenSelectExecutor
  ))
