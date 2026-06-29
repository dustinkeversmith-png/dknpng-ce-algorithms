package engine.context.operation

enum ContextOperationCapability:
  case AddsFact
  case ResolvesUnknown
  case GeneratesGoal
  case ProducesArtifact
  case TranslatesRepresentation
  case MeasuresContext
  case OptimizesCandidate