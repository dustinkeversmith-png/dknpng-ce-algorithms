package engine.problem.profiles.ProgrammingProblem

object ProgrammingProblemExamples:
  val twoSumJsonic: String =
    """
    {
      // JSONic conveniences are supported: comments and trailing commas.
      "id": "two-sum-basic",
      "kind": "algorithm",
      "domain": "arrays",
      "title": "Two Sum",
      "description": "Given an array of integers and a target, return indices of two numbers that add up to the target.",
      "input.spec": {
        "nums": "Array[Int]",
        "target": "Int"
      },
      "output.spec": "Array[Int] of length 2 containing the selected indices",
      "constraints": [
        "Exactly one valid answer exists",
        "Cannot use the same element twice",
        "Expected O(n) time",
      ],
      "examples": [
        {
          "input": { "nums": [2, 7, 11, 15], "target": 9 },
          "output": [0, 1],
          "explanation": "nums[0] + nums[1] = 9"
        }
      ],
      "edgeCases": [
        "two elements only",
        "negative numbers",
        "target found near end"
      ],
      "complexity.target": {
        "time": "O(n)",
        "space": "O(n)"
      },
      "requiredBehavior": [
        "return two different indices",
        "do not reuse the same array element"
      ]
    }
    """
