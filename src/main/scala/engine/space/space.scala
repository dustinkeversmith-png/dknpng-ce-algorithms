package problem.space

// 1. Import the mutable package
import scala.collection.mutable.HashMap
import value.*


/**
 * Typeclass defining the Ambient / Valid State Space for state type `S`.
 */
// =========================================================================
// 2. SPACE TRAIT (Modular Space Definition Driven by Invariants)
// =========================================================================



/**
 * Core typeclass / specification defining an Ambient and Valid State Space.
 */
trait Space:
  /** Human-readable description */
  def description: String

  /** Structural shape descriptor eg dimensionality and length which is not a string anymore, an array of integers will suffice, with each describing the length of the dimensions. */


  /** A complete value type descriptor of what kind of objects are stored in here. */
  def value_type: ValueType

  // This will be valueType description



  /** List of all semantic invariants associated with this Space eg. "Sum of row elemnts must equal 1.0"*/
  def semantic_invariants: List[Invariant]

  /** List of all structural invariants associated with this Space eg. in terms of sizes and shapes of the thing and type, columns same size" */
  def structural_invariants: List[Invariant]

  /** All invariants evaluated when checking or validating a Value. */
  def invariants: List[Invariant] = this.structural_invariants ++ this.semantic_invariants

  /** Constructive generator: guaranteed structurally well-formed */
  /** Guaranteed to the produced generation satisfies the semantic_invariants and structural invariants and is a part of this space */
  def generate(): Value

  /** Optional generator/enumeration for discrete or bounded spaces */
  def enumerate: LazyList[Value] = LazyList.empty

  /** Checks if `s` satisfies all space invariants */
  def contains(s: Value): Boolean =


    // Same type structural layout check.
    // structural and semantic values associated with each sub type or field would also make alot more sense
    invariants.forall(_.holds(s))

  /** Validates a value and reports every violated invariant. */
  def validate(s: Value): Either[List[String], Value] =
    val errors = invariants.filterNot(_.holds(s)).map(inv => s"[${inv.name}]: ${inv.violationMessage(s)}")
    if errors.isEmpty then Right(s) else Left(errors)

  // No validation or anything like that, no errors involved.

  // --- Topology & Local Search (New) ---
  /** Local neighborhood perturbation generator */
  /** Some kind of topology, possibly simply just a inverse vector similarity on the memory layout for starters as a default */
  /** Or having some kind of way of navigating the space obviously ints is just +1 % max or something*/
  def neighbors(s: Value): LazyList[Value] = LazyList.empty
  // Need a intelligent way to topologically traverse the space with our invariants, we can now solidly iterate through any type type, but know we need incrementers, or known dimensionality in the value, also the invariants/constraints will most likely factor into this calcualtion

  /** Distance metric between two states in this space */
  def distance(a: Value, b: Value): Double = 0.0

  /** Project / clamp an out-of-bounds state back into the valid envelope */
  def project(s: Value): Value = s

  /** Refine this space by attaching an additional invariant */
  def withInvariant(inv: Invariant): Space =
    val parent = this
    new Space:
      def description = parent.description
      def value_type = parent.value_type
      def semantic_invariants = parent.semantic_invariants :+ inv
      def structural_invariants = parent.structural_invariants
      def generate() =
        val candidate = parent.generate()
        if contains(candidate) then candidate
        else enumerate.headOption.getOrElse(throw new IllegalStateException(s"Generator could not satisfy refinement '${inv.name}'"))
      override def enumerate = parent.enumerate.filter(contains)
      override def neighbors(s: Value) = parent.neighbors(s).filter(contains)
      override def distance(a: Value, b: Value) = parent.distance(a, b)
      override def project(s: Value) =
        val candidate = parent.project(s)
        if contains(candidate) then candidate else enumerate.headOption.getOrElse(candidate)
