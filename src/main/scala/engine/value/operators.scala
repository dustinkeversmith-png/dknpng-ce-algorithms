
trait ValueOperators:

  // Map[String, Functional(Value a, Value b, ... However many values) -> Value] operator_set

  // Then can map independent operator overrides to the operator_set as functions.
  def operatorSet: HashMap[String, Functional]

 // Sets the operator under the name.
  def register(
    name: String,
    functional: Functional
  ): Unit =
    operatorSet(name) = functional

  // 
  def operator(name: String): Functional =
    operatorSet(name)

  def execute(
    name: String,
    arguments: Map[String, Value]
  ): Value =
    operatorSet(name).execute(arguments)


//   ["cast"] = 
//     Functional(
//         // So assuming we know the underlying name of a function say value, we then accept the line seperations, then it goes and executes this with a functional ast
//         "value += other_value * 2.0"
//         "intermediary ="

//     )
    