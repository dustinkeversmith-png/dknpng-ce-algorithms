package engine.problem.parsing
import engine.problem.parsing.ProblemFormatError

/**
 * Converts an external problem representation into the engine's typed problem layer.
 *
 * The name is intentionally small and functional: each implementation maps one input
 * representation into one normalized output representation without mutating engine state.
 */
trait ProblemFormatFunctor[-Input, +Output]:
  def map(input: Input): Either[ProblemFormatError, Output]
