package engine.context

final case class ContextDelta(
  addFacts: Map[String, ContextValue] = Map.empty,
  removeFacts: Set[String] = Set.empty,
  addUnknowns: Map[String, ContextValue] = Map.empty,
  removeUnknowns: Set[String] = Set.empty,
  addGoals: Vector[String] = Vector.empty,
  addArtifacts: Map[String, ContextValue] = Map.empty
):
  def isEmpty: Boolean =
    addFacts.isEmpty &&
      removeFacts.isEmpty &&
      addUnknowns.isEmpty &&
      removeUnknowns.isEmpty &&
      addGoals.isEmpty &&
      addArtifacts.isEmpty

  def combine(other: ContextDelta): ContextDelta =
    ContextDelta(
      addFacts = addFacts ++ other.addFacts,
      removeFacts = removeFacts ++ other.removeFacts,
      addUnknowns = addUnknowns ++ other.addUnknowns,
      removeUnknowns = removeUnknowns ++ other.removeUnknowns,
      addGoals = addGoals ++ other.addGoals,
      addArtifacts = addArtifacts ++ other.addArtifacts
    )

object ContextDelta:
  val empty: ContextDelta = ContextDelta()
