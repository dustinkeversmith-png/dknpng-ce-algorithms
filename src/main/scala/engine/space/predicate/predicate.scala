package problem.space

import scala.annotation.unchecked.uncheckedVariance

/**
 * Named predicate S => Boolean with Boolean combinators.
 */
case class Predicate[-S](name: String, test: S => Boolean):
  def apply(s: S): Boolean = test(s)

  def &&(other: Predicate[S @uncheckedVariance]): Predicate[S] =
    Predicate(s"(${name} ∧ ${other.name})", s => this.test(s) && other.test(s))

  def ||(other: Predicate[S @uncheckedVariance]): Predicate[S] =
    Predicate(s"(${name} ∨ ${other.name})", s => this.test(s) || other.test(s))

  def unary_! : Predicate[S] =
    Predicate(s"¬(${name})", s => !this.test(s))
