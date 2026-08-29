package problem.space

import value.*

/**
 * First-class invariant with diagnostic reporting and Boolean combinators.
 */
case class Invariant(
  name: String,
  predicate: Predicate,
  violationMessage: Value => String = (s: Value) => "Violated invariant"
):
  def holds(s: Value): Boolean = predicate(s)

  /** Conjunction (∧): Both compiled invariant programs must hold */
  def &&(other: Invariant): Invariant =
    Invariant(
      s"(${name} ∧ ${other.name})",
      this.predicate && other.predicate,
      s => 
        if !this.predicate(s) then this.violationMessage(s)
        else other.violationMessage(s)
    )

  /** Disjunction (∨): At least one compiled invariant program must hold */
  def ||(other: Invariant): Invariant =
    Invariant(
      s"(${name} ∨ ${other.name})",
      this.predicate || other.predicate,
      s => s"${this.violationMessage(s)} AND ${other.violationMessage(s)}"
    )

  /** Negation (¬): Inverts the compiled predicate AST */
  def unary_! : Invariant =
    Invariant(
      s"¬(${name})",
      !this.predicate,
      s => s"Failed negation: '${name}' was unexpectedly true"
    )
