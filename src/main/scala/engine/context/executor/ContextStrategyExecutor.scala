package engine.context.executor

import engine.context.Context
import engine.context.strategy.ContextStrategy

trait ContextStrategyExecutor:
  def execute(ContextStrategy: ContextStrategy, context: Context, budget: ContextExecutionBudget): ContextExecutionResult
