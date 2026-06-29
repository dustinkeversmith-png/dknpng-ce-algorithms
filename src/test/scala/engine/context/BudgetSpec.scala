package engine.context

import engine.context.Context
import engine.context.registry.ContextExampleRegistries
import engine.context.executor.ContextExecutionBudget
import engine.context.interpreter.ContextStrategyInterpreter
import engine.context.strategy.*

class BudgetSpec extends munit.FunSuite:
  test("consumes step per atomic execution"):
    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val ContextStrategy = ContextAtomicStrategy(ContextStrategyId("type"), "type", ContextStrategyKind.Meta, "add-problem-type")

    val result = executor.execute(ContextStrategy, Context(), ContextExecutionBudget(3, 3))
    assert(result.success)
    assertEquals(result.remainingBudget.remainingSteps, 2)

  test("fails when max steps reached"):
    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val ContextStrategy = ContextAtomicStrategy(ContextStrategyId("type"), "type", ContextStrategyKind.Meta, "add-problem-type")

    val result = executor.execute(ContextStrategy, Context(), ContextExecutionBudget(0, 3))
    assert(!result.success)

  test("respects max depth for composite strategies"):
    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val nested = ContextCompositeStrategy(
      ContextStrategyId("outer"), "outer", ContextStrategyKind.Meta, ContextCompositionMode.Sequence,
      Vector(ContextCompositeStrategy(
        ContextStrategyId("inner"), "inner", ContextStrategyKind.Meta, ContextCompositionMode.Sequence,
        Vector(ContextAtomicStrategy(ContextStrategyId("type"), "type", ContextStrategyKind.Meta, "add-problem-type"))
      ))
    )

    val result = executor.execute(nested, Context(), ContextExecutionBudget(10, 1))
    assert(!result.success)
