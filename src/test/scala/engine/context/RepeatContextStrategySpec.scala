package engine.context

import engine.context.Context
import engine.context.registry.ContextExampleRegistries
import engine.context.executor.ContextExecutionBudget
import engine.context.interpreter.ContextStrategyInterpreter
import engine.context.strategy.*
import engine.context.trace.ContextTraceEvent

class RepeatStrategySpec extends munit.FunSuite:
  test("repeats child fixed number of times"):
    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val ContextStrategy = ContextCompositeStrategy(
      ContextStrategyId("repeat"), "Repeat", ContextStrategyKind.Meta, ContextCompositionMode.Repeat,
      Vector(ContextAtomicStrategy(ContextStrategyId("subgoal"), "subgoal", ContextStrategyKind.Meta, "generate-search-subgoal")),
      repeatLimit = Some(3)
    )

    val result = executor.execute(ContextStrategy, Context(), ContextExecutionBudget(10, 10))

    assert(result.success)
    assertEquals(result.context.goals.size, 3)
    assertEquals(result.trace.events.count(_.isInstanceOf[ContextTraceEvent.RepeatIteration]), 3)

  test("stops on budget exhaustion"):
    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val ContextStrategy = ContextCompositeStrategy(
      ContextStrategyId("repeat-budget"), "Repeat", ContextStrategyKind.Meta, ContextCompositionMode.Repeat,
      Vector(ContextAtomicStrategy(ContextStrategyId("subgoal"), "subgoal", ContextStrategyKind.Meta, "generate-search-subgoal")),
      repeatLimit = Some(10)
    )

    val result = executor.execute(ContextStrategy, Context(), ContextExecutionBudget(3, 10))
    assert(!result.success)
