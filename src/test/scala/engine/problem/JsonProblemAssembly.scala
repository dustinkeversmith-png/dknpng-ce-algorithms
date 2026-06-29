package engine.problem

import engine.context.ContextValue
import engine.context.registry.ContextExampleRegistries
import engine.context.executor.ContextExecutionBudget
import engine.context.interpreter.ContextStrategyInterpreter
import engine.context.strategy.*
import engine.problem.profiles.ProgrammingProblem.{JsonProgrammingProblemFunctor, ProgrammingProblemExamples}

class JsonProblemAssembly extends munit.FunSuite:
  test("loads a JSONic programming problem and runs a basic assembly"):
    val context = JsonProgrammingProblemFunctor.map(ProgrammingProblemExamples.twoSumJsonic).toOption.get
      .copy(facts = Map(
        "candidates" -> ContextValue.list("a", "b", "c"),
        "target" -> ContextValue.ContextStringValue("b")
      ))

    val assembly = ContextCompositeStrategy(
      ContextStrategyId("json-problem-assembly"),
      "JSON Problem Assembly",
      ContextStrategyKind.Search,
      ContextCompositionMode.Sequence,
      Vector(ContextAtomicStrategy(ContextStrategyId("search"), "search", ContextStrategyKind.Search, "basic-search"))
    )

    val result = ContextStrategyInterpreter(ContextExampleRegistries.finiteSearchOperations)
      .execute(assembly, context, ContextExecutionBudget(10, 10))

    assert(result.success)
    assertEquals(result.context.facts("search.result"), ContextValue.ContextStringValue("b"))
