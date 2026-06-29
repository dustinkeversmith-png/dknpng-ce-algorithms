package engine.context

import engine.context.{ContextValue, Context}
import engine.context.executor.ContextExecutionBudget
import engine.context.interpreter.ContextStrategyInterpreter
import engine.context.operation.prefabs.ContextAddFactOperation
import engine.context.registry.ContextExampleRegistries
import engine.context.strategy.*
import engine.context.registry.ContextOperationRegistry

class ContextAtomicStrategySpec extends munit.FunSuite:
  test("executes operation by ID and applies context delta"):
    val registry = ContextOperationRegistry.withOperations(
      ContextAddFactOperation("add-x", "x", ContextValue.ContextStringValue("ok"))
    )
    val executor = ContextStrategyInterpreter(registry)
    val ContextStrategy = ContextAtomicStrategy(ContextStrategyId("s1"), "Add x", ContextStrategyKind.Meta, "add-x")

    val result = executor.execute(ContextStrategy, Context(), ContextExecutionBudget(5, 5))

    assert(result.success)
    assertEquals(result.context.facts("x"), ContextValue.ContextStringValue("ok"))
    assert(result.trace.nonEmpty)

  test("fails if operation ID is missing"):
    val executor = ContextStrategyInterpreter(ContextOperationRegistry.empty)
    val ContextStrategy = ContextAtomicStrategy(ContextStrategyId("missing"), "Missing", ContextStrategyKind.Meta, "does-not-exist")

    val result = executor.execute(ContextStrategy, Context(), ContextExecutionBudget(5, 5))

    assert(!result.success)
    assert(result.error.nonEmpty)
