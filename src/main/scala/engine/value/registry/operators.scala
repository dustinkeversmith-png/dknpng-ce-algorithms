// Import Functionals

import scala.collection.mutable.HashMap


final case class ArgumentMap(arguments: Map[String, String]) // Typename and parameter name.

// name is name of the operation, and arguments is the argument signature
final case class FunctionalId(name: String, arguments: Map[String, String])

// so that way we can find the matching Functional based on the name of the operator and types of the function signature.

trait ValueOperators:

  // Map[String, Functional(Value a, Value b, ... However many values) -> Value] operator_set
  def operatorSet: HashMap[FunctionalId, Functional]

  // Then can map independent operator overrides to the operator_set as functions.
  // Sets the operator under the name.
  def register(
    name: String,
    args: ArgumentMap,
    functional: Functional
  ): Unit =

    // Functional should probably cache its build if its buildable.
    // We can create a functionalId from the name and args signature.
    operatorSet(FunctionalId(name, args.arguments)) = functional

  //
  def operator(name: String, args: Map[String, Value]): Functional =

    // The args will then end up being string mode and Values in this case which the values will probably be mutated so they wont really be copied around but possibly referenced.
    operatorSet.collectFirst {
      case (id, functional) if id.name == name && id.arguments.keySet == args.keySet => functional
    }.getOrElse(throw new NoSuchElementException(s"Unknown operator '$name' for arguments ${args.keySet.mkString(", ")}"))

    // Find the Functional
    // Compile it into the syntactic and ast form
    // Then execute it using the functional
    // functional.Execture

    // The return type can be "Return","Type" in the arg map if a return type is expected, but the operation can always return any Value as in generic value type.

// Usage for base types.
// basetypes.register_operator("double", ["x","a", "b"], "x = a + b;")

// Usage for value types
//value.register_operator("mass_upgrade", ["TypeName A", "TypeName B"], "A.mass += B.mass; A.mass /= 2; ")

// Theoretically then if mass is lets say a Value with basetype, it will go and find the basetype registry to complete the remaining calcultations
// If it is a ValueType that is not a base type it will use the nested Operators probably attached to that ValueType to complete or resolve the remaining calculations.
