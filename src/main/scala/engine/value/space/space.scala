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

  /** One compiled invariant program used by both validation and synthesis. */
  def unified_invariant: Invariant =
    require(this.invariants.nonEmpty, "A generative Space requires at least one compiled invariant")
    this.invariants.tail.foldLeft(this.invariants.head)((combined, invariant) => combined && invariant)

  /** Constructive generator: guaranteed structurally well-formed */
  /** Guaranteed to the produced generation satisfies the semantic_invariants and structural invariants and is a part of this space */
  def generator: Generator

  /** Execute the Space's synthesized generator program. */
  def generate(): Value = this.generator.generate(s"${this.value_type.name}_generated")

  /** Optional generator/enumeration for discrete or bounded spaces */
  def enumerate: LazyList[Value] = LazyList.empty

  /** Checks if `s` satisfies all space invariants */
  def contains(s: Value): Boolean =
    // Same type structural layout check.
    // structural and semantic values associated with each sub type or field would also make alot more sense
    invariants.forall(_.holds(s))

  /** Set Intersection: S₁ ∩ S₂ */
  def intersect(that: Space): Space =
    require(this.value_type.name == that.value_type.name, "Cannot intersect incompatible ValueTypes")
    val self = this
    new Space:
      def description = s"(${self.description} ∩ ${that.description})"
      def value_type = self.value_type
      def structural_invariants = (self.structural_invariants ++ that.structural_invariants).distinct
      def semantic_invariants = (self.semantic_invariants ++ that.semantic_invariants).distinct
      val generator: Generator =
        val composed = new Generator(value_type, unified_invariant.predicate, math.max(self.generator.maximum_models, that.generator.maximum_models))
        composed.terminal_grammar = self.generator.terminal_grammar.clone()
        that.generator.terminal_grammar.foreach { case (typeName, terminals) =>
          if !composed.terminal_grammar.contains(typeName) then composed.terminal_grammar(typeName) = terminals
        }
        composed.synthesize()
        composed

      override def enumerate: LazyList[Value] = self.enumerate.filter(that.contains)

  /** Set Union: S₁ ∪ S₂ */
  def union(that: Space): Space =
    require(this.value_type.name == that.value_type.name, "Cannot unite incompatible ValueTypes")
    val self = this
    new Space:
      def description = s"(${self.description} ∪ ${that.description})"
      def value_type = self.value_type
      // Union invariants: Structural requirements must hold for either branch
      def structural_invariants = self.structural_invariants.filter(that.structural_invariants.contains)
      def semantic_invariants = List(
        Invariant(
          s"(${self.description} || ${that.description})",
          self.unified_invariant.predicate || that.unified_invariant.predicate,
          v => s"Value violated both spaces in union"
        )
      )

      override def contains(s: Value): Boolean = self.contains(s) || that.contains(s)

      val generator: Generator =
        val composed = new Generator(value_type, unified_invariant.predicate, self.generator.maximum_models + that.generator.maximum_models)
        composed.terminal_grammar = self.generator.terminal_grammar.clone()
        that.generator.terminal_grammar.foreach { case (typeName, terminals) =>
          if !composed.terminal_grammar.contains(typeName) then composed.terminal_grammar(typeName) = terminals
        }
        composed.synthesize()
        composed

      override def enumerate: LazyList[Value] =
        self.enumerate.zipAll(that.enumerate, null, null).flatMap {
          case (a, b) => List(Option(a), Option(b)).flatten
        }.distinctBy(_.memory_offset)

  /** Set Difference: S₁ \ S₂ */
  def diff(that: Space): Space =
    require(this.value_type.name == that.value_type.name, "Cannot subtract incompatible ValueTypes")
    val self = this
    new Space:
      def description = s"(${self.description} \\ ${that.description})"
      def value_type = self.value_type
      def structural_invariants = self.structural_invariants
      def semantic_invariants = self.semantic_invariants :+ Invariant(
        s"NOT(${that.description})",
        !that.unified_invariant.predicate,
        v => s"Value fell inside excluded subspace ${that.description}"
      )
      val generator: Generator =
        val composed = new Generator(value_type, unified_invariant.predicate, self.generator.maximum_models)
        composed.terminal_grammar = self.generator.terminal_grammar.clone()
        composed.synthesize()
        composed

      override def enumerate: LazyList[Value] = self.enumerate.filterNot(that.contains)

  // =========================================================================
  // Topology, Neighborhoods, and Metrics
  // =========================================================================

  /** Metric d(a, b) over memory buffer elements */
  def distance(a: Value, b: Value): Double =
    if a.shape.nonEmpty then
      (0 until a.shape.product).map { idx =>
        val va = a.registry.caster.retrieve("double", a.reference_element(Array(idx)))
        val vb = b.registry.caster.retrieve("double", b.reference_element(Array(idx)))
        math.pow(va - vb, 2)
      }.sum
    else
      math.abs(
        a.registry.caster.retrieve("double", a) - b.registry.caster.retrieve("double", b)
      )

  /** Generates topological discrete/continuous neighbors within distance epsilon */
  def neighbors(s: Value, step: Double = 1.0): LazyList[Value] =
    if !contains(s) then LazyList.empty
    else
      val perturbations = LazyList(step, -step)
      perturbations.flatMap { delta =>
        val neighbor = new Value(s"${s.name}_neighbor", s)
        neighbor.attach_registry(s.registry)
        // Perturb primitive values or tensor dimensions
        if s.shape.nonEmpty then
          (0 until s.shape.product).to(LazyList).map { idx =>
            val clone = new Value(s"${s.name}_n$idx", s)
            clone.attach_registry(s.registry)
            val curr = s.registry.caster.retrieve("double", s.reference_element(Array(idx)))
            clone.reference_element(Array(idx)).operator("=")(s.registry.caster.cast("double", curr + delta))
            project(clone)
          }
        else
          val curr = s.registry.caster.retrieve("double", s)
          neighbor.operator("=")(s.registry.caster.cast("double", curr + delta))
          LazyList(project(neighbor))
      }.filter(contains)

  // =========================================================================
  // Projections
  // =========================================================================

  /** Clamps or projects an arbitrary state back into the invariant-satisfying envelope */
  def project(s: Value): Value =
    if contains(s) then s
    else
      // Nearest valid neighbor projection via enumeration/distance minimizing
      enumerate.minByOption(cand => distance(s, cand)).getOrElse(s)

  /** Subspace projection described by its own compiled invariant and synthesized generator program. */
  def projectSubspace(
    targetType: ValueType,
    projectedInvariant: Invariant,
    terminalGrammar: HashMap[String, Vector[GeneratorTerminal]],
    maximumModels: Int = 32
  ): Space =
    val parent = this
    new Space:
      def description = s"Proj_${targetType.name}(${parent.description})"
      def value_type = targetType
      def structural_invariants = Nil
      def semantic_invariants = List(projectedInvariant)
      val generator: Generator =
        val projectedGenerator = new Generator(targetType, projectedInvariant.predicate, maximumModels)
        projectedGenerator.terminal_grammar = terminalGrammar.clone()
        projectedGenerator.synthesize()
        projectedGenerator
