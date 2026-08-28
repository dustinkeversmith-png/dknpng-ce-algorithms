package problem.space

/**
 * First-class invariant with diagnostic reporting and Boolean combinators.
 */
case class Invariant(
  name: String,
  predicate: Predicate,
  violationMessage: Value => String = (s: Value) => "Violated invariant"
):
  def holds(s: Value): Boolean = predicate(s)

  /** Conjunction (∧): Both invariants must hold */
  def &&(other: Invariant): Invariant =
    Invariant(
      s"(${name} ∧ ${other.name})",
      Predicate(s"(${name} ∧ ${other.name})", (s: Value) => this.predicate(s) && other.predicate(s)),
      s => 
        if !this.predicate(s) then this.violationMessage(s)
        else other.violationMessage(s)
    )

  /** Disjunction (∨): At least one invariant must hold */
  def ||(other: Invariant): Invariant =
    Invariant(
      s"(${name} ∨ ${other.name})",
      Predicate(s"(${name} ∨ ${other.name})", (s: Value) => this.predicate(s) || other.predicate(s)),
      s => s"${this.violationMessage(s)} AND ${other.violationMessage(s)}"
    )

  /** Negation (¬): Inverts the predicate */
  def unary_! : Invariant =
    Invariant(
      s"¬(${name})",
      Predicate(s"¬(${name})", (s: Value) => !this.predicate(s)),
      s => s"Failed negation: '${name}' was unexpectedly true"
    )
