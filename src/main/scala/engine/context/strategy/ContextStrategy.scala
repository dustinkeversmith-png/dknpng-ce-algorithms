package engine.context.strategy

import engine.context.operation.ContextOperationCapability

final case class ContextStrategyId(value: String) derives CanEqual:
  override def toString: String = value

enum ContextCompositionMode derives CanEqual:
  case Sequence
  case Fallback
  case Choice
  case Repeat
  case MeasureThenSelect

/** Broad context-solving domain for a ContextStrategy. */
enum ContextStrategyKind derives CanEqual:
  case Classical
  case Symbolic
  case Search
  case Simulation
  case Translation
  case Quantum
  case Hybrid
  case Meta

/** Optional compatibility metadata for selecting context strategies. */
final case class ContextStrategyCapability(
  domains: Set[String] = Set.empty,
  requiredFacts: Set[String] = Set.empty,
  producedFacts: Set[String] = Set.empty,
  requiredCapabilities: Set[ContextOperationCapability] = Set.empty
):
  def isCompatibleWith(availableFacts: Set[String], capabilities: Set[ContextOperationCapability]): Boolean =
    requiredFacts.subsetOf(availableFacts) && requiredCapabilities.subsetOf(capabilities)

/** Names the semantic role a context ContextStrategy plays in a larger assembly. */
enum ContextStrategyRole derives CanEqual:
  case ContextPreparation
  case ContextProblemAnalysis
  case SolutionPlanning
  case SolutionOperation
  case Validation
  case Translation

sealed trait ContextStrategy derives CanEqual:
  def id: ContextStrategyId
  def name: String
  def kind: ContextStrategyKind
  def metadata: ContextStrategyCapability

final case class ContextAtomicStrategy(
  id: ContextStrategyId,
  name: String,
  kind: ContextStrategyKind,
  operationId: String,
  metadata: ContextStrategyCapability = ContextStrategyCapability()
) extends ContextStrategy

final case class ContextCompositeStrategy(
  id: ContextStrategyId,
  name: String,
  kind: ContextStrategyKind,
  compositionMode: ContextCompositionMode,
  children: Vector[ContextStrategy],
  metadata: ContextStrategyCapability = ContextStrategyCapability(),
  repeatLimit: Option[Int] = None
) extends ContextStrategy

final case class ContextStrategyAssembly(
  id: String,
  name: String,
  root: ContextStrategy
)
