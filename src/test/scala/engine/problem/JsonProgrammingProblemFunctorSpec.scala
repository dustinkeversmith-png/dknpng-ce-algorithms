package engine.problem

import engine.context.ContextValue
import engine.problem.profiles.ProgrammingProblem.{JsonProgrammingProblemFunctor, ProgrammingProblemExamples}

class JsonProgrammingProblemFunctorSpec extends munit.FunSuite:
  test("converts direct JSONic programming problem into normalized Context"):
    val result = JsonProgrammingProblemFunctor.map(ProgrammingProblemExamples.twoSumJsonic)

    assert(result.isRight, result.left.toOption.map(_.toString).getOrElse(""))
    val context = result.toOption.get

    assertEquals(context.facts("profile"), ContextValue.ContextStringValue("programming-problem/v1"))
    assertEquals(context.facts("problem.id"), ContextValue.ContextStringValue("two-sum-basic"))
    assertEquals(context.facts("problem.kind"), ContextValue.ContextStringValue("algorithm"))
    assertEquals(context.facts("problem.domain"), ContextValue.ContextStringValue("arrays"))
    assert(context.goals.contains("derive solution ContextStrategy"))
    assert(context.unknowns.contains("solution.algorithm"))

  test("converts array into ProblemSpace"):
    val input = s"""
      {
        "problems": [
          ${ProgrammingProblemExamples.twoSumJsonic},
          {
            "id": "valid-parentheses",
            "kind": "algorithm",
            "domain": "stack",
            "title": "Valid Parentheses",
            "description": "Determine whether all brackets are closed in the correct order.",
            "input.spec": { "s": "String" },
            "output.spec": "Boolean"
          }
        ]
      }
    """

    val result = JsonProblemSpaceFunctor.map(input)

    assert(result.isRight, result.left.toOption.map(_.toString).getOrElse(""))
    assertEquals(result.toOption.get.size, 2)

  test("accepts existing Context tagged JSON"):
    val input = """
      {
        "facts": {
          "problem.id": { "ContextStringValue": "p1" },
          "title": { "ContextStringValue": "Already Normalized" },
          "description": { "ContextStringValue": "A context-shaped problem." },
          "problem.kind": { "ContextStringValue": "meta" }
        },
        "unknowns": {},
        "goals": ["test goal"],
        "artifacts": {}
      }
    """

    val result = JsonProgrammingProblemFunctor.map(input)

    assert(result.isRight, result.left.toOption.map(_.toString).getOrElse(""))
    assertEquals(result.toOption.get.goals, Vector("test goal"))
    assertEquals(result.toOption.get.facts("problem.id"), ContextValue.ContextStringValue("p1"))
