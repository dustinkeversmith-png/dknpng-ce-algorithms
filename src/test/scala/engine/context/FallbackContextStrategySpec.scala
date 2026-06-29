package engine.context

import engine.context.{ContextValue, Context}
import engine.context.registry.ContextExampleRegistries
import engine.context.executor.ContextExecutionBudget
import engine.context.interpreter.ContextStrategyInterpreter
import engine.context.strategy.*

class FallbackStrategySpec extends munit.FunSuite:
  test("tries fallback when first child fails"):
    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val ContextStrategy = ContextCompositeStrategy(
      ContextStrategyId("fallback"), "Fallback", ContextStrategyKind.Meta, ContextCompositionMode.Fallback,
      Vector(
        ContextAtomicStrategy(ContextStrategyId("fail-child"), "fail", ContextStrategyKind.Meta, "fail"),
        ContextAtomicStrategy(ContextStrategyId("type"), "type", ContextStrategyKind.Meta, "add-problem-type")
      )
    )

    val result = executor.execute(ContextStrategy, Context(), ContextExecutionBudget(10, 10))

    assert(result.success)
    assertEquals(result.context.facts("problem.type"), ContextValue.ContextStringValue("finite-search"))

  test("fails only if all children fail"):
    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val ContextStrategy = ContextCompositeStrategy(
      ContextStrategyId("fallback-all-fail"), "Fallback", ContextStrategyKind.Meta, ContextCompositionMode.Fallback,
      Vector(
        ContextAtomicStrategy(ContextStrategyId("fail-a"), "fail a", ContextStrategyKind.Meta, "fail"),
        ContextAtomicStrategy(ContextStrategyId("fail-b"), "fail b", ContextStrategyKind.Meta, "fail")
      )
    )

    val result = executor.execute(ContextStrategy, Context(), ContextExecutionBudget(10, 10))
    assert(!result.success)
