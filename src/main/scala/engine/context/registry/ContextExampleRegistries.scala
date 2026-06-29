package engine.context.registry

import engine.context.ContextValue
import engine.context.operation.prefabs.*

object ContextExampleRegistries:
  val finiteSearchOperations: ContextOperationRegistry = ContextOperationRegistry.withOperations(
    ContextAddFactOperation("add-problem-type", "problem.type", ContextValue.ContextStringValue("finite-search")),
    ContextGenerateSubgoalOperation("generate-search-subgoal", "search candidates"),
    ContextBasicSearchOperation("basic-search", "candidates", "target", "search.result"),
    ContextResolveUnknownOperation("resolve-target", "target", "target"),
    ContextFailOperation("fail", "Intentional fallback test failure")
  )
