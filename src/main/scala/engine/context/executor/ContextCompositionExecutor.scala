package engine.context.executor

import engine.context.Context
import engine.context.strategy.ContextCompositeStrategy

trait ContextCompositionExecutor:
  def execute(
    ContextStrategy: ContextCompositeStrategy,
    context: Context,
    budget: ContextExecutionBudget,
    executor: ContextStrategyExecutor
  ): ContextExecutionResult
