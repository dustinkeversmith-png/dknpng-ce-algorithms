package problem.space

import value.*

/**
 * Named predicate S => Boolean with Boolean combinators.
 */
case class Predicate(name: String, test: Value => Boolean):
  def apply(s: Value): Boolean = test(s)

  def &&(other: Predicate): Predicate =
    Predicate(s"(${name} ∧ ${other.name})", s => this.test(s) && other.test(s))

  def ||(other: Predicate): Predicate =
    Predicate(s"(${name} ∨ ${other.name})", s => this.test(s) || other.test(s))

  def unary_! : Predicate =
    Predicate(s"¬(${name})", s => !this.test(s))
