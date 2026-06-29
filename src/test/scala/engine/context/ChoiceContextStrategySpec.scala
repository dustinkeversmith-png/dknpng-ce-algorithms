package engine.context

import engine.context.{ContextValue, Context}
import engine.context.registry.ContextExampleRegistries
import engine.context.executor.ContextExecutionBudget
import engine.context.interpreter.ContextStrategyInterpreter
import engine.context.strategy.*
import engine.context.trace.ContextTraceEvent

class ChoiceStrategySpec extends munit.FunSuite:
  test("selects and executes first child in V1"):
    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val ContextStrategy = ContextCompositeStrategy(
      ContextStrategyId("choice"), "Choice", ContextStrategyKind.Meta, ContextCompositionMode.Choice,
      Vector(
        ContextAtomicStrategy(ContextStrategyId("type"), "type", ContextStrategyKind.Meta, "add-problem-type"),
        ContextAtomicStrategy(ContextStrategyId("subgoal"), "subgoal", ContextStrategyKind.Meta, "generate-search-subgoal")
      )
    )

    val result = executor.execute(ContextStrategy, Context(), ContextExecutionBudget(10, 10))

    assert(result.success)
    assert(result.context.facts.contains("problem.type"))
    assert(result.context.goals.isEmpty)
    assert(result.trace.events.exists(_.isInstanceOf[ContextTraceEvent.ChoiceSelected]))
