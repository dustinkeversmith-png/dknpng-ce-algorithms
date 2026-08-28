package problem.space

// 1. Import the mutable package
import scala.collection.mutable.HashMap


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

  /** Structural shape descriptor eg dimensionality and length which is not a string anymore, an array of integers will suffice, with each describing the length of the dimensions. */

  /** [1] For example when it is a set of singular entities such as real numbers, etc*/
  def shape: String

  

  /** A complete value type descriptor of what kind of objects are stored in here. */
  def value_type: Any = description


  /** List of semantic and structural invariants governing this space */
  def invariants: List[Invariant[S]]

  /** List of all semantic invariants associated with this Space eg. "Sum of row elemnts must equal 1.0"*/
  def semantic_invariants: List[Invariant[S]] = invariants

  /** List of all structural invariants associated with this Space eg. in terms of sizes and shapes of the thing and type, columns same size" */
  def structural_invariants: List[Invariant[S]] = List.empty


  


  

  /** Constructive generator: guaranteed structurally well-formed */
  /** Guaranteed to the produced generation satisfies the semantic_invariants and structural invariants and is a part of this space */
  def generate(): S

  /** Optional generator/enumeration for discrete or bounded spaces */
  def enumerate: LazyList[S] = LazyList.empty

  /** Checks if `s` satisfies all space invariants */
  def contains(s: S): Boolean =


    // Same type structural layout check.
    // structural and semantic values associated with each sub type or field would also make alot more sense
    invariants.forall(_.holds(s))

  /** Validates a value and reports every violated invariant. */
  def validate(s: S): Either[List[String], S] =
    val errors = invariants.filterNot(_.holds(s)).map(inv => s"[${inv.name}]: ${inv.violationMessage(s)}")
    if errors.isEmpty then Right(s) else Left(errors)

  // No validation or anything like that, no errors involved.

  // --- Topology & Local Search (New) ---
  /** Local neighborhood perturbation generator */
  /** Some kind of topology, possibly simply just a inverse vector similarity on the memory layout for starters as a default */
  /** Or having some kind of way of navigating the space obviously ints is just +1 % max or something*/
  def neighbors(s: S): LazyList[S] = LazyList.empty

  /** Distance metric between two states in this space */
  def distance(a: S, b: S): Double = 0.0

  /** Project / clamp an out-of-bounds state back into the valid envelope */
  def project(s: S): S = s

  /** Refine this space by attaching an additional invariant */
  def withInvariant(inv: Invariant[S]): Space[S] =
    val parent = this
    new Space[S]:
      def description = parent.description
      def shape = parent.shape
      def invariants = parent.invariants :+ inv
      def generate() =
        val candidate = parent.generate()
        if contains(candidate) then candidate
        else enumerate.headOption.getOrElse(throw new IllegalStateException(s"Generator could not satisfy refinement '${inv.name}'"))
      override def enumerate = parent.enumerate.filter(contains)
      override def neighbors(s: S) = parent.neighbors(s).filter(contains)
      override def distance(a: S, b: S) = parent.distance(a, b)
      override def project(s: S) =
        val candidate = parent.project(s)
        if contains(candidate) then candidate else enumerate.headOption.getOrElse(candidate)
