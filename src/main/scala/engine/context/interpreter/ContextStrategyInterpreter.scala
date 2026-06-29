package engine.context.interpreter

import engine.context.Context
import engine.context.registry.ContextCompositionExecutorRegistry
import engine.context.executor.{ContextExecutionBudget, ContextExecutionError, ContextExecutionResult, ContextStrategyExecutor}
import engine.context.strategy.*
import engine.context.registry.ContextOperationRegistry
import engine.context.trace.{ContextTrace, ContextTraceEvent}

/**
 * Interpreter for problem-ContextStrategy assemblies.
 *
 * This layer executes strategies that prepare, analyze, or enrich a Context.
 * It is intentionally separate from future solution-ContextStrategy execution, where generated
 * algorithm/code operations can have their own topology and runtime.
 */
class ContextStrategyInterpreter(
  ContextOperationRegistry: ContextOperationRegistry,
  ContextCompositionExecutors: ContextCompositionExecutorRegistry = ContextCompositionExecutorRegistry.default
) extends ContextStrategyExecutor:

  override def execute(ContextStrategy: ContextStrategy, context: Context, budget: ContextExecutionBudget): ContextExecutionResult =
    ContextStrategy match
      case atomic: ContextAtomicStrategy => executeAtomic(atomic, context, budget)
      case composite: ContextCompositeStrategy => executeComposite(composite, context, budget)

  private def executeAtomic(ContextStrategy: ContextAtomicStrategy, context: Context, budget: ContextExecutionBudget): ContextExecutionResult =
    val started = ContextTrace.empty.append(ContextTraceEvent.StrategyStarted(ContextStrategy.id, ContextStrategy.name))

    budget.consumeStep match
      case Left(error) =>
        ContextExecutionResult.failure(context, started.append(ContextTraceEvent.StrategyFailed(ContextStrategy.id, error.message)), error, budget)
      case Right(afterStepBudget) =>
        val withBudgetTrace = started.append(ContextTraceEvent.BudgetConsumed(ContextStrategy.id, afterStepBudget.remainingSteps, afterStepBudget.currentDepth))
        ContextOperationRegistry.get(ContextStrategy.operationId) match
          case None =>
            val error = ContextExecutionError.OperationNotFound(ContextStrategy.operationId)
            ContextExecutionResult.failure(context, withBudgetTrace.append(ContextTraceEvent.StrategyFailed(ContextStrategy.id, error.message)), error, afterStepBudget)
          case Some(operation) =>
            operation.run(context) match
              case Left(error) =>
                ContextExecutionResult.failure(context, withBudgetTrace.append(ContextTraceEvent.StrategyFailed(ContextStrategy.id, error.message)), error, afterStepBudget)
              case Right(delta) =>
                val updatedContext = context.applyDelta(delta)
                val trace = withBudgetTrace
                  .append(ContextTraceEvent.DeltaApplied(ContextStrategy.id, delta))
                  .append(ContextTraceEvent.StrategySucceeded(ContextStrategy.id))
                ContextExecutionResult.success(updatedContext, trace, afterStepBudget)

  private def executeComposite(ContextStrategy: ContextCompositeStrategy, context: Context, budget: ContextExecutionBudget): ContextExecutionResult =
    val started = ContextTrace.empty.append(ContextTraceEvent.StrategyStarted(ContextStrategy.id, ContextStrategy.name))

    val enteredBudgetEither = for
      afterStep <- budget.consumeStep
      afterDepth <- afterStep.enterDepth
    yield afterDepth

    enteredBudgetEither match
      case Left(error) =>
        ContextExecutionResult.failure(context, started.append(ContextTraceEvent.StrategyFailed(ContextStrategy.id, error.message)), error, budget)
      case Right(enteredBudget) =>
        val budgetTrace = started.append(ContextTraceEvent.BudgetConsumed(ContextStrategy.id, enteredBudget.remainingSteps, enteredBudget.currentDepth))
        val compositionResult = ContextCompositionExecutors.get(ContextStrategy.compositionMode) match
          case Some(contextCompositionExecutor) => contextCompositionExecutor.execute(ContextStrategy, context, enteredBudget, this)
          case None => ContextExecutionResult.failure(context, ContextTrace.empty, ContextExecutionError.UnsupportedComposition(ContextStrategy.compositionMode.toString), enteredBudget)

        val completedBudget = compositionResult.remainingBudget.exitDepth
        val completedTrace =
          if compositionResult.success then
            budgetTrace ++ compositionResult.trace.append(ContextTraceEvent.StrategySucceeded(ContextStrategy.id))
          else
            val reason = compositionResult.error.map(_.message).getOrElse("Composite failed")
            budgetTrace ++ compositionResult.trace.append(ContextTraceEvent.StrategyFailed(ContextStrategy.id, reason))

        compositionResult.copy(trace = completedTrace, remainingBudget = completedBudget)
