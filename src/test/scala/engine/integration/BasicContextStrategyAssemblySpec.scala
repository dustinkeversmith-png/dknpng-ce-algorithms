package engine.integration

import engine.context.{ContextValue, Context}
import engine.context.registry.ContextExampleRegistries
import engine.context.executor.ContextExecutionBudget
import engine.context.interpreter.ContextStrategyInterpreter
import engine.context.strategy.*

class BasicContextStrategyAssemblySpec extends munit.FunSuite:
  test("finite search ContextStrategy assembly passes first sprint scenario"):
    val context = Context(
      facts = Map(
        "candidates" -> ContextValue.list("a", "b", "c", "d"),
        "target" -> ContextValue.ContextStringValue("c")
      )
    )

    val assembly = ContextCompositeStrategy(
      id = ContextStrategyId("finite-search-assembly"),
      name = "Finite Search Assembly",
      kind = ContextStrategyKind.Search,
      compositionMode = ContextCompositionMode.Sequence,
      children = Vector(
        ContextAtomicStrategy(ContextStrategyId("add-type"), "Add problem type", ContextStrategyKind.Meta, "add-problem-type"),
        ContextAtomicStrategy(ContextStrategyId("subgoal"), "Generate subgoal", ContextStrategyKind.Meta, "generate-search-subgoal"),
        ContextAtomicStrategy(ContextStrategyId("search"), "Basic search", ContextStrategyKind.Search, "basic-search")
      )
    )

    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val result = executor.execute(assembly, context, ContextExecutionBudget(maxSteps = 20, maxDepth = 10))

    assert(result.success)
    assertEquals(result.context.facts("problem.type"), ContextValue.ContextStringValue("finite-search"))
    assertEquals(result.context.facts("search.result"), ContextValue.ContextStringValue("c"))
    assert(result.context.goals.contains("search candidates"))
    assert(result.trace.nonEmpty)
    assert(result.remainingBudget.remainingSteps < 20)
