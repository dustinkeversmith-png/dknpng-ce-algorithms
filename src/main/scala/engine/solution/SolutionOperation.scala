package engine.solution

/** A generated solution instruction; unlike context operations, it does not mutate Context. */
sealed trait SolutionOperation derives CanEqual:
  def id: String
  def instruction: String
  def children: Vector[SolutionOperation]

final case class SolutionInstruction(
  id: String,
  instruction: String,
  children: Vector[SolutionOperation] = Vector.empty
) extends SolutionOperation

final case class SolutionControl(
  id: String,
  instruction: String,
  children: Vector[SolutionOperation]
) extends SolutionOperation

