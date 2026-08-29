import scala.collection.mutable.HashMap


// Import Functionals

// Typename and parameter name.

// name is name of the operation, and arguments is the argument signature
// so that way we can find the matching Functional based on the name of the operator and types of the function signature.

type OperatorFunction = (FunctionalId, Value, Vector[Value]) => Value


// Functional programs still use this as their external name and argument signature.
final case class FunctionalId(name: String, arguments: Map[String, String])


// Each registered value type owns a set of bootstrap functions.
// Functional can replace these functions later without changing Value dispatch.
final class ValueOperators:

  // Map[String, Functional(Value a, Value b, ... However many values) -> Value] operator_set
  var operator_set: HashMap[String, (FunctionalId, OperatorFunction)] = HashMap.empty

  // Then can map independent operator overrides to the operator_set as functions.
  // Sets the operator under the name.
  def register(id: FunctionalId, operator: OperatorFunction): Unit =
    // Functional should probably cache its build if its buildable.
    // We can create a functionalId from the name and args signature.

    // Here are going to parse a unique string describing the function which will be 
    var fid_hash = id.name
    val argumentTypes = id.arguments.values.toVector
    var argumentIndex = 0

    while argumentIndex < argumentTypes.length do
      fid_hash += s"_${argumentTypes(argumentIndex)}"
      argumentIndex += 1
    // second is the type name of the argument, but it also has names so we can then keep track of which variable is which just in case.

    // here we store the FunctionalId along side the operator function for ease of passing the arguments 
    this.operator_set(fid_hash) = (id, operator)

  //
  def operator(name: String, arguments: Vector[Value]): Value =
    // The args will then end up being string mode and Values in this case which the values will probably be mutated so they wont really be copied around but possibly referenced.
    require(arguments.nonEmpty, s"Operator '$name' requires at least one Value argument")

    var fid_hash = name
    var argumentIndex = 0
    while argumentIndex < arguments.length do
      fid_hash += s"_${arguments(argumentIndex).t}"
      argumentIndex += 1

    val (id, operatorFunction) = this.operator_set.getOrElse(
      fid_hash,
      throw new NoSuchElementException(s"Unknown operator overload '$fid_hash'")
    )

    require(
      id.arguments.size == arguments.size,
      s"Operator '${id.name}' expected ${id.arguments.size} arguments but received ${arguments.size}"
    )
    operatorFunction(id, arguments.head, arguments.tail)

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
