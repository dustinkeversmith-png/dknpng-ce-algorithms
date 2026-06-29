package engine.context.executor

import engine.context.Context
import engine.context.strategy.ContextStrategyId

final case class ContextExecutionStep(
  ContextStrategyId: ContextStrategyId,
  before: Context,
  after: Context,
  result: Either[ContextExecutionError, Context]
)
