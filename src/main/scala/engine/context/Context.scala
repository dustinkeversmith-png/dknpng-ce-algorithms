package engine.context

/**
 * Immutable context carried through problem-ContextStrategy execution.
 *
 * The context is intentionally split into four channels:
 *   - facts: known values, input specifications, derived measurements, and metadata
 *   - unknowns: named slots that still need discovery or resolution
 *   - goals: human-readable goal statements used by the current ContextStrategy layer
 *   - artifacts: larger generated outputs such as code, patches, proofs, traces, or plans
 *
 * Higher-level profiles can layer typed vocabularies on top of these generic channels
 * without forcing the ContextStrategy engine to know about every problem domain.
 */
final case class Context(
  facts: Map[String, ContextValue] = Map.empty,
  unknowns: Map[String, ContextValue] = Map.empty,
  goals: Vector[String] = Vector.empty,
  artifacts: Map[String, ContextValue] = Map.empty
):
  def applyDelta(delta: ContextDelta): Context =
    ContextDeltaApplicator.default.apply(this, delta)

  def fact(key: String): Option[ContextValue] = facts.get(key)
  def unknown(key: String): Option[ContextValue] = unknowns.get(key)
  def artifact(key: String): Option[ContextValue] = artifacts.get(key)

  // Get default input, number of inputs etc
  // 

  def withFact(key: String, value: ContextValue): Context =
    copy(facts = facts + (key -> value))

  def withUnknown(key: String, placeholder: ContextValue = ContextValue.ContextNullValue): Context =
    copy(unknowns = unknowns + (key -> placeholder))

  def withGoal(goal: String): Context =
    copy(goals = goals :+ goal)

  def withArtifact(key: String, artifact: ContextValue): Context =
    copy(artifacts = artifacts + (key -> artifact))

/** Applies a delta to a context. Kept separate so future merge policies can be tested independently. */
trait ContextDeltaApplicator:
  def apply(context: Context, delta: ContextDelta): Context

object ContextDeltaApplicator:
  val default: ContextDeltaApplicator = new ContextDeltaApplicator:
    override def apply(context: Context, delta: ContextDelta): Context =
      val removedFacts = context.facts -- delta.removeFacts
      val removedUnknowns = context.unknowns -- delta.removeUnknowns
      context.copy(
        facts = removedFacts ++ delta.addFacts,
        unknowns = removedUnknowns ++ delta.addUnknowns,
        goals = context.goals ++ delta.addGoals,
        artifacts = context.artifacts ++ delta.addArtifacts
      )
