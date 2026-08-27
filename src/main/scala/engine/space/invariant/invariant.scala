package problem.space

/**
 * First-class invariant with diagnostic reporting and Boolean combinators.
 */
case class Invariant[S](
  name: String,
  predicate: Predicate[S],
  violationMessage: S => String = (s: S) => "Violated invariant"
):
  def holds(s: S): Boolean = predicate(s)

  /** Conjunction (∧): Both invariants must hold */
  def &&(other: Invariant[S]): Invariant[S] =
    Invariant(
      s"(${name} ∧ ${other.name})",
      Predicate(s"(${name} ∧ ${other.name})", (s: S) => this.predicate(s) && other.predicate(s)),
      s => 
        if !this.predicate(s) then this.violationMessage(s)
        else other.violationMessage(s)
    )

  /** Disjunction (∨): At least one invariant must hold */
  def ||(other: Invariant[S]): Invariant[S] =
    Invariant(
      s"(${name} ∨ ${other.name})",
      Predicate(s"(${name} ∨ ${other.name})", (s: S) => this.predicate(s) || other.predicate(s)),
      s => s"${this.violationMessage(s)} AND ${other.violationMessage(s)}"
    )

  /** Negation (¬): Inverts the predicate */
  def unary_! : Invariant[S] =
    Invariant(
      s"¬(${name})",
      Predicate(s"¬(${name})", (s: S) => !this.predicate(s)),
      s => s"Failed negation: '${name}' was unexpectedly true"
    )
