package engine.app

import engine.context.{ContextValue, Context}
import engine.context.executor.ContextExecutionBudget
import engine.context.interpreter.ContextStrategyInterpreter
import engine.context.registry.ContextExampleRegistries
import engine.context.strategy.*

object DemoRunner:
  def main(args: Array[String]): Unit =
    val context = Context(
      facts = Map(
        "candidates" -> ContextValue.list("a", "b", "c", "d"),
        "target" -> ContextValue.ContextStringValue("c"),
        "input.spec" -> ContextValue.list("candidates", "target"),
        "output.spec" -> ContextValue.ContextStringValue("search.result")
      ),
      goals = Vector("find target candidate")
    )

    val assembly = ContextCompositeStrategy(
      id = ContextStrategyId("finite-search-assembly"),
      name = "Finite Search Context Assembly",
      kind = ContextStrategyKind.Search,
      compositionMode = ContextCompositionMode.Sequence,
      children = Vector(
        ContextAtomicStrategy(ContextStrategyId("add-type"), "Add problem type", ContextStrategyKind.Meta, "add-problem-type"),
        ContextAtomicStrategy(ContextStrategyId("add-subgoal"), "Generate search subgoal", ContextStrategyKind.Meta, "generate-search-subgoal"),
        ContextAtomicStrategy(ContextStrategyId("search"), "Basic search", ContextStrategyKind.Search, "basic-search")
      )
    )

    val executor = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
    val result = executor.execute(assembly, context, ContextExecutionBudget(maxSteps = 20, maxDepth = 10))

    println(s"success: ${result.success}")
    println(s"problem.type: ${result.context.facts.get("problem.type")}")
    println(s"search.result: ${result.context.facts.get("search.result")}")
    println(s"goals: ${result.context.goals.mkString(", ")}")
    println(s"trace events: ${result.trace.size}")
