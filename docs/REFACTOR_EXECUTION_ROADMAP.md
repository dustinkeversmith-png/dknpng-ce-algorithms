# Refactor Execution Roadmap

## 1. High-Priority Structural Bottlenecks

### ProblemContext.scala
The highest priority bottleneck was the overloaded meaning of `facts`, `unknowns`, and `goals`. The refactor keeps the original constructor stable but formalizes the four channels and extracts delta application into `ProblemContextDeltaApplicator`. This gives future phases a testable place to introduce different merge policies without rewriting the context model.

### StrategyKind.scala and StrategyOperation.scala
`StrategyKind` mixed domain labels and action labels. The refactor keeps the existing enum for compatibility, then adds `StrategyCompatibility` and `StrategyRole` so future assemblers can select by applicability and role instead of overloading the kind enum. `StrategyOperation` is now a compatibility alias for `ProblemStrategicOperation`, and inspectable `OperationStep` / `OperationPlan` types define the first solution-topology seam.

### StrategyInterpreter.scala
The interpreter previously directly selected every composition executor. The refactor introduces `engine.strategy.interpreter.ProblemStrategyInterpreter` and `CompositionExecutorRegistry`. The original `StrategyInterpreter` remains as a backwards-compatible wrapper.

### BasicSearchOperation.scala
The basic search operation was an opaque operation. It is now a planned operation with explicit steps: require candidates, require target, resolve result. This directly addresses the need for granular operation topology.

### JsonContextValueCodec.scala and ProblemSpace.scala
The JSON codec remains source-compatible while adding an explicit `engine.problem.json` facade package. `ProblemSpace` now supports variables and subspaces in addition to raw context lists, which better represents fixed/unfixed problem dimensions and generated examples.

### ChoiceExecutor.scala
Choice execution now delegates selection to `ChoiceSelectionPolicy`. The default behavior remains first-child selection, so current tests and behavior are preserved.

## 2. Order of Execution (Batched Dependency Roadmap)

### Batch 1 — Core Data Model and Format Boundaries
Files:
- `ProblemContext.scala`
- `ProblemContextDelta.scala`
- `ProblemSpace.scala`
- `JsonContextValueCodec.scala`
- `engine/problem/json/JsonCodecs.scala`

Why first:
These are low-level dependencies used by almost every other layer. Stabilizing context updates, JSON conversion, and problem-space semantics makes later strategy/interpreter work safer.

### Batch 2 — Strategy Semantics and Operation Topology
Files:
- `StrategyKind.scala`
- `StrategyOperation.scala`
- `engine/strategy/operation/ProblemOperationStep.scala`
- `BasicSearchOperation.scala`
- `ExampleRegistries.scala`

Why second:
Once the data layer is stable, operations can become more granular without forcing the interpreter or composition executors to know about step internals.

### Batch 3 — Interpreter and Composition Rewiring
Files:
- `StrategyInterpreter.scala`
- `engine/strategy/interpreter/ProblemStrategyInterpreter.scala`
- `CompositionExecutorRegistry.scala`
- `ChoiceExecutor.scala`
- `ChoiceSelectionPolicy.scala`

Why third:
Interpreter rewiring is safer after operation contracts are stable. The registry abstraction removes direct interpreter coupling to concrete composition executors.

### Batch 4 — Demos, Tests, and Documentation Cleanup
Files:
- `DemoRunner.scala`
- `ProgrammingProblemExamples.scala`
- `JsonProblemAssembly.scala`
- `docs/REFACTOR_EXECUTION_ROADMAP.md`

Why fourth:
These files should validate the architecture rather than drive it. After the core, operation, and interpreter layers are decoupled, examples can be cleaned up and repointed at the new seams.

## 3. Targeted Execution Prompts for Later Implementation Agents

### Batch 1 Prompt
You are refactoring the problem context and format boundary layer. Inspect `ProblemContext.scala`, `ProblemContextDelta.scala`, `ProblemSpace.scala`, and `JsonContextValueCodec.scala`. Remove speculative architectural comments after implementing their intent. Preserve the public `ProblemContext(facts, unknowns, goals, artifacts)` constructor. Extract context delta application into a separate applicator abstraction. Extend `ProblemSpace` so it can represent many contexts plus fixed/unfixed/generated variables and subspaces. Add a JSON package facade without breaking existing imports. Run all problem-format tests and fix any compatibility issues.

### Batch 2 Prompt
You are refactoring the strategy semantic and operation topology layer. Inspect `StrategyKind.scala`, `StrategyOperation.scala`, and the example operations. Do not remove existing enum cases or operation registry compatibility. Add separate compatibility/role metadata so `StrategyKind` is not forced to represent both domain and behavior. Rename the conceptual operation contract to `ProblemStrategicOperation` while keeping `StrategyOperation` source-compatible. Add `OperationStep` and `OperationPlan`, then refactor `BasicSearchOperation` into a planned operation with separate required-input and resolve-result steps. Remove completed TODO/speculation comments.

### Batch 3 Prompt
You are refactoring the interpreter/composition layer. Inspect `StrategyInterpreter.scala`, `ChoiceExecutor.scala`, and the composition executors. Introduce a `ProblemStrategyInterpreter` in an interpreter package and keep `StrategyInterpreter` as a compatibility wrapper. Add a `CompositionExecutorRegistry` so new composition modes can be registered without editing a hardcoded match in the interpreter. Refactor `ChoiceExecutor` to use a `ChoiceSelectionPolicy`, preserving first-child behavior by default. Run sequence, fallback, choice, repeat, budget, and registry tests.

### Batch 4 Prompt
You are cleaning up demos, examples, and integration tests. Inspect `DemoRunner.scala`, `ProgrammingProblemExamples.scala`, and `JsonProblemAssembly.scala`. Replace speculative comments with executable examples or documentation. Make the demo context include explicit input and output specs. Add a JSON problem assembly test that loads a programming problem context and executes a basic strategy assembly. Ensure all comments left in these files explain behavior rather than future work.
