package engine.solution

final case class SolutionDelta(
  addValues: Map[String, SolutionValue] = Map.empty,
  removeValues: Set[String] = Set.empty,
  addOperations: Vector[SolutionOperation] = Vector.empty,
  addArtifacts: Map[String, SolutionValue] = Map.empty
):
  def isEmpty: Boolean =
    addValues.isEmpty && removeValues.isEmpty && addOperations.isEmpty && addArtifacts.isEmpty

  def combine(other: SolutionDelta): SolutionDelta =
    SolutionDelta(
      addValues = addValues ++ other.addValues,
      removeValues = removeValues ++ other.removeValues,
      addOperations = addOperations ++ other.addOperations,
      addArtifacts = addArtifacts ++ other.addArtifacts
    )

object SolutionDelta:
  val empty: SolutionDelta = SolutionDelta()
