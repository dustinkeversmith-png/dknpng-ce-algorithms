package engine.context

import engine.context.{ContextValue, Context}
import engine.context.registry.ContextExampleRegistries
import engine.context.executor.ContextExecutionBudget
import engine.context.interpreter.ContextStrategyInterpreter
import engine.context.strategy.*

class SequenceStrategySpec extends munit.FunSuite:
  test("executes children in order and passes updated context"):
    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val context = Context(
      facts = Map(
        "candidates" -> ContextValue.list("a", "b", "c"),
        "target" -> ContextValue.ContextStringValue("b")
      )
    )
    val ContextStrategy = ContextCompositeStrategy(
      ContextStrategyId("seq"),
      "Sequence",
      ContextStrategyKind.Search,
      ContextCompositionMode.Sequence,
      Vector(
        ContextAtomicStrategy(ContextStrategyId("type"), "type", ContextStrategyKind.Meta, "add-problem-type"),
        ContextAtomicStrategy(ContextStrategyId("search"), "search", ContextStrategyKind.Search, "basic-search")
      )
    )

    val result = executor.execute(ContextStrategy, context, ContextExecutionBudget(10, 10))

    assert(result.success)
    assertEquals(result.context.facts("problem.type"), ContextValue.ContextStringValue("finite-search"))
    assertEquals(result.context.facts("search.result"), ContextValue.ContextStringValue("b"))

  test("fails if child fails"):
    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val ContextStrategy = ContextCompositeStrategy(
      ContextStrategyId("seq-fail"), "Sequence Fail", ContextStrategyKind.Search, ContextCompositionMode.Sequence,
      Vector(
        ContextAtomicStrategy(ContextStrategyId("fail-child"), "fail", ContextStrategyKind.Meta, "fail"),
        ContextAtomicStrategy(ContextStrategyId("type"), "type", ContextStrategyKind.Meta, "add-problem-type")
      )
    )

    val result = executor.execute(ContextStrategy, Context(), ContextExecutionBudget(10, 10))
    assert(!result.success)
    assert(!result.context.facts.contains("problem.type"))
