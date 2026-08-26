package problem.space

/**
 * Typeclass defining the Ambient / Valid State Space for state type `S`.
 */
// =========================================================================
// 2. SPACE TRAIT (Modular Space Definition Driven by Invariants)
// =========================================================================

/**
 * Core typeclass / specification defining an Ambient and Valid State Space.
 */
trait Space[S]:
  /** Human-readable description */
  def description: String

  /** Structural shape descriptor (e.g., "Vector[4] of (Interval[0.0, 1.0])") */
  def shape: String

  /** List of semantic and structural invariants governing this space */
  def invariants: List[Invariant[S]]

  /** Constructive generator: guaranteed structurally well-formed */
  def generate(): S

  /** Optional generator/enumeration for discrete or bounded spaces */
  def enumerate: LazyList[S] = LazyList.empty

  /** Checks if `s` satisfies all space invariants */
  def contains(s: S): Boolean =
    invariants.forall(_.holds(s))

  /** Validates `s`, returning diagnostic errors on violation */
  def validate(s: S): Either[List[String], S] =
    val errors = invariants.filterNot(_.holds(s)).map(inv => s"[${inv.name}]: ${inv.violationMessage(s)}")
    if errors.isEmpty then Right(s) else Left(errors)

  // --- Topology & Local Search (New) ---
  /** Local neighborhood perturbation generator */
  def neighbors(s: S): LazyList[S] = LazyList.empty

  /** Distance metric between two states in this space */
  def distance(a: S, b: S): Double = 0.0

  /** Project / clamp an out-of-bounds state back into the valid envelope */
  def project(s: S): S = s

  /** Refine this space by attaching an additional invariant */
  def withInvariant(inv: Invariant[S]): Space[S] = RefinedSpace(this, inv)

/** A refinement that preserves the topology and generators of its parent. */
final case class RefinedSpace[S](underlying: Space[S], invariant: Invariant[S]) extends Space[S]:
  def description: String = underlying.description
  def shape: String = underlying.shape
  def invariants: List[Invariant[S]] = underlying.invariants :+ invariant
  def generate(): S =
    val candidate = underlying.generate()
    if contains(candidate) then candidate
    else enumerate.find(contains).getOrElse(
      throw new IllegalStateException(s"Generator could not satisfy refinement '${invariant.name}'")
    )
  override def enumerate: LazyList[S] = underlying.enumerate.filter(contains)
  override def neighbors(s: S): LazyList[S] = underlying.neighbors(s).filter(contains)
  override def distance(a: S, b: S): Double = underlying.distance(a, b)
  override def project(s: S): S =
    val projected = underlying.project(s)
    if contains(projected) then projected
    else enumerate.find(contains).getOrElse(projected)
