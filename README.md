# Adaptive Problem Space Strategy Engine — Phase 1

This is the Phase 1 implementation slice for the Adaptive Problem Space Strategy Engine.

It implements the first stable foundation:

```txt
Context
  → Strategy
  → Execution
  → Trace
  → Tests
```

## What is included

- `ContextValue`
- `ProblemContext`
- `ProblemContextDelta`
- `StrategyId`
- `StrategyKind`
- `StrategyCapability`
- `CompositionMode`
- `AtomicStrategy`
- `CompositeStrategy`
- `StrategyAssembly`
- `StrategyOperation`
- `OperationRegistry`
- `StrategyRegistry`
- `ExecutionBudget`
- `ExecutionError`
- `ExecutionResult`
- `StrategyTrace`
- `TraceEvent`
- `TraceRecorder`
- `StrategyExecutor`
- `StrategyInterpreter`
- composition executors:
  - sequence
  - fallback
  - choice
  - repeat
- example operations:
  - add fact
  - resolve unknown
  - generate subgoal
  - basic search
  - fail operation for fallback tests
- munit tests
- demo runner

## Run tests

```bash
sbt test
```

## Run demo

```bash
sbt "runMain engine.app.DemoRunner"
```

Expected demo result:

```txt
success: true
problem.type: finite-search
search.result: c
goals: search candidates
```

## Design rule

Strategies are serializable plan objects. Operations contain executable behavior.

```txt
Strategy = declarative algorithm fragment
Operation = executable behavior resolved by operation registry
```

Strategies return `ProblemContextDelta` objects. They do not mutate the context directly.
