package problem.space

import scala.collection.mutable.HashMap
import value.*

/**
 * Named predicate represented by parsed syntax and its compiled semantic program.
 *
 * A predicate is never stored as a Scala `Value => Boolean` function. Running a
 * predicate always means evaluating its FunctionalSemanticTree with the supplied
 * Value attached to argument_name.
 */
final class Predicate(
  var name: String,
  var syntax: FunctionalTree,
  var argument_name: String
):
  var tree: FunctionalSemanticTree = new FunctionalSemanticTree()
  this.tree.build(this.syntax)

  def this(name: String, syntax: FunctionalTree) =
    this(name, syntax, "candidate")

  def this(name: String, source: String, argument_name: String) =
    this(
      name,
      parseProgram(source) match
        case fastparse.Parsed.Success(parsed, _) => parsed
        case failure: fastparse.Parsed.Failure =>
          throw new IllegalArgumentException(failure.trace().longMsg),
      argument_name
    )

  def this(name: String, source: String) =
    this(name, source, "candidate")

  def apply(value: Value): Boolean =
    val result = new Evaluator(
      this.tree,
      HashMap[String, Value](this.argument_name -> value)
    ).evaluate().getOrElse(
      throw new IllegalStateException(s"Predicate '${this.name}' did not return a Value")
    )
    result.registry.caster.retrieve(result.base_type_name(), result) != 0.0

  def returned_expression(): Expr =
    var statementIndex = this.syntax.statements.length - 1
    while statementIndex >= 0 do
      this.syntax.statements(statementIndex) match
        case ReturnStatement(Some(expression)) => return expression
        case _ =>
      statementIndex -= 1
    throw new IllegalArgumentException(s"Predicate '${this.name}' does not contain a returned expression")

  def supporting_statements(): Vector[Expr] =
    this.syntax.statements.filter {
      case ReturnStatement(_) => false
      case _ => true
    }

  def compose(other: Predicate, operator: String, composedName: String): Predicate =
    require(
      this.argument_name == other.argument_name,
      s"Cannot compose predicates using '${this.argument_name}' and '${other.argument_name}'"
    )
    val statements =
      this.supporting_statements() ++
        other.supporting_statements() :+
        ReturnStatement(Some(BinaryOp(this.returned_expression(), operator, other.returned_expression())))
    new Predicate(composedName, FunctionalTree(statements), this.argument_name)

  def &&(other: Predicate): Predicate =
    this.compose(other, "&&", s"(${this.name} ∧ ${other.name})")

  def ||(other: Predicate): Predicate =
    this.compose(other, "||", s"(${this.name} ∨ ${other.name})")

  def unary_! : Predicate =
    val statements = this.supporting_statements() :+
      ReturnStatement(Some(UnaryOp("!", this.returned_expression())))
    new Predicate(s"¬(${this.name})", FunctionalTree(statements), this.argument_name)
