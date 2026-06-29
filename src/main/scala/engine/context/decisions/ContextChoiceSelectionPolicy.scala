package engine.context.decisions

import engine.context.Context
import engine.context.executor.ContextExecutionBudget
import engine.context.strategy.{ContextCompositeStrategy, ContextStrategy}

/** Selects one child from a composite choice ContextStrategy. */
trait ContextChoiceSelectionPolicy:
  def select(ContextStrategy: ContextCompositeStrategy, context: Context, budget: ContextExecutionBudget): Option[ContextStrategy]

object ContextChoiceSelectionPolicy:
  val firstChild: ContextChoiceSelectionPolicy = new ContextChoiceSelectionPolicy:
    override def select(ContextStrategy: ContextCompositeStrategy, context: Context, budget: ContextExecutionBudget): Option[ContextStrategy] =
      ContextStrategy.children.headOption
