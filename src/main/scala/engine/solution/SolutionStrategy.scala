package engine.solution

final case class SolutionStrategyId(value: String) derives CanEqual:
  override def toString: String = value

enum SolutionCompositionMode derives CanEqual:
  case Sequence
  case Fallback
  case Choice
  case Repeat
  case MeasureThenSelect

enum SolutionStrategyKind derives CanEqual:
  case Classical
  case Symbolic
  case Search
  case Simulation
  case Translation
  case Quantum
  case Hybrid
  case Meta

final case class SolutionStrategyCapability(
  domains: Set[String] = Set.empty,
  requiredInputs: Set[String] = Set.empty,
  producedArtifacts: Set[String] = Set.empty,
  requiredOperations: Set[String] = Set.empty
) derives CanEqual

enum SolutionStrategyRole derives CanEqual:
  case SolutionPlanning
  case SolutionOperation
  case Validation
  case Translation
  case Assembly

final case class SolutionStrategy(
  id: SolutionStrategyId,
  name: String,
  kind: SolutionStrategyKind,
  operations: Vector[SolutionOperation],
  metadata: SolutionStrategyCapability = SolutionStrategyCapability(),
  role: SolutionStrategyRole = SolutionStrategyRole.SolutionOperation
)

final case class SolutionAssembly(
  id: String,
  name: String,
  root: SolutionStrategy
)
