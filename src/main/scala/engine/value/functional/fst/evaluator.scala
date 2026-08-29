// We will have our operator nodes, assignments, all that stuff up here

package value

import scala.collection.mutable.HashMap

trait Evalulator:

    def evaluate(): Option[Value]
        // Traverse the FunctionalAst
        // The actual FunctionalTree will have like an array of in order AstTrees parsed from the parser
        // So we iterate through those, do the evaluation/traversal of the nodes, mutating the arguments internal values, which may more may not trigger a recursive call for a different functional associated with something else but ignore that
        // Nodes will have like variable names in them, so we can then use the args to associate those of course.
        // Also the internal vars like temp vars can be created on the vars tree in the program and reused of course.


final class Evaluator(var tree: FunctionalSemanticTree) extends Evalulator:

    var args: HashMap[String, Value] = HashMap.empty
    var stack: HashMap[String, Value] = HashMap.empty
    var returned: Option[Value] = None
    var has_returned: Boolean = false
    var scopes: Vector[HashMap[String, Value]] = Vector.empty

    def this(tree: FunctionalSemanticTree, args: HashMap[String, Value]) =
        this(tree)
        this.args = args

    def this(tree: FunctionalSemanticTree, args: HashMap[String, Value], stack: HashMap[String, Value]) =
        this(tree)
        this.args = args
        this.stack = stack

    def evaluate(): Option[Value] =
        this.returned = None
        this.has_returned = false
        this.evaluate_program(this.tree.program)
        this.returned

    def evaluate_program(program: ProgramNode): Option[Value] =
        var evaluatedValue: Option[Value] = None
        var statementIndex = 0

        while statementIndex < program.statements.length && !this.has_returned do
            program.statements(statementIndex) match
                case function: FunctionDeclarationNode => this.tree.functions(function.name) = function
                case statement => evaluatedValue = Some(this.evaluate_node(statement))
            statementIndex += 1

        this.returned.orElse(evaluatedValue)

    def evaluate_block(block: BlockNode): Option[Value] =
        var evaluatedValue: Option[Value] = None
        var statementIndex = 0

        while statementIndex < block.statements.length && !this.has_returned do
            evaluatedValue = Some(this.evaluate_node(block.statements(statementIndex)))
            statementIndex += 1

        this.returned.orElse(evaluatedValue)

    def find_value(name: String): Value =
        var scopeIndex = this.scopes.length - 1
        while scopeIndex >= 0 do
            if this.scopes(scopeIndex).contains(name) then return this.scopes(scopeIndex)(name)
            scopeIndex -= 1

        this.stack.get(name)
            .orElse(this.args.get(name))
            .getOrElse(throw new NoSuchElementException(s"Unknown value: $name"))

    def store_value(name: String, value: Value): Unit =
        if this.scopes.nonEmpty then this.scopes.last(name) = value
        else this.stack(name) = value

    def find_registry(typeName: String): TypeRegistry =
        var scopeIndex = this.scopes.length - 1
        while scopeIndex >= 0 do
            val values = this.scopes(scopeIndex).valuesIterator
            while values.hasNext do
                val registry = values.next().registry
                if registry.sizes.contains(typeName) || registry.operators.contains(typeName) then return registry
            scopeIndex -= 1

        val stackValues = this.stack.valuesIterator
        while stackValues.hasNext do
            val registry = stackValues.next().registry
            if registry.sizes.contains(typeName) || registry.operators.contains(typeName) then return registry

        val argumentValues = this.args.valuesIterator
        while argumentValues.hasNext do
            val registry = argumentValues.next().registry
            if registry.sizes.contains(typeName) || registry.operators.contains(typeName) then return registry

        throw new NoSuchElementException(s"No Value provides a registry for type '$typeName'")

    def declared_value(name: String, valueType: String): Value =
        var matchingValue: Option[Value] = None
        var scopeIndex = this.scopes.length - 1

        while scopeIndex >= 0 && matchingValue.isEmpty do
            val scopeValues = this.scopes(scopeIndex).valuesIterator
            while scopeValues.hasNext && matchingValue.isEmpty do
                val value = scopeValues.next()
                if valueType == "Value" || value.t == valueType then matchingValue = Some(value)
            scopeIndex -= 1

        val stackValues = this.stack.valuesIterator
        while stackValues.hasNext && matchingValue.isEmpty do
            val value = stackValues.next()
            if valueType == "Value" || value.t == valueType then matchingValue = Some(value)

        val argumentValues = this.args.valuesIterator
        while argumentValues.hasNext && matchingValue.isEmpty do
            val value = argumentValues.next()
            if valueType == "Value" || value.t == valueType then matchingValue = Some(value)

        matchingValue match
            case Some(value) =>
                val declaredValue = new Value(name, value)
                declaredValue.attach_registry(value.registry)
            case None =>
                if valueType == "Value" then
                    throw new NoSuchElementException(s"A generic Value declaration '$name' requires a runtime Value to provide its structure")
                val registry = this.find_registry(valueType)
                val declaredValue = new Value(name, Vector.empty, Map("value" -> valueType))
                declaredValue.t = valueType
                declaredValue.attach_registry(registry)

    def condition_is_true(value: Value): Boolean =
        value.registry.caster.retrieve(value.base_type_name(), value) != 0.0

    def index_coordinate(node: SemanticNode): Int =
        node match
            case NumericLiteralNode(value) => value.toInt
            case _ =>
                val indexValue = this.evaluate_node(node)
                indexValue.registry.caster.retrieve(indexValue.base_type_name(), indexValue).toInt

    def evaluate_function(function: FunctionDeclarationNode, arguments: Vector[Value]): Value =
        require(function.parameters.length == arguments.length, s"Function '${function.name}' expected ${function.parameters.length} arguments but received ${arguments.length}")

        val functionScope = HashMap.empty[String, Value]
        var parameterIndex = 0
        while parameterIndex < function.parameters.length do
            val parameter = function.parameters(parameterIndex)
            val argument = arguments(parameterIndex)
            require(
                parameter.valueType == "Value" || argument.t == parameter.valueType || argument.base_type_name() == parameter.valueType,
                s"Function '${function.name}' parameter '${parameter.name}' expected ${parameter.valueType} but received ${argument.t}"
            )
            functionScope(parameter.name) = argument
            parameterIndex += 1

        val outerReturned = this.returned
        val outerHasReturned = this.has_returned
        this.returned = None
        this.has_returned = false
        this.scopes = this.scopes :+ functionScope

        val evaluatedBody = this.evaluate_block(function.body)
        val functionResult = this.returned.orElse(evaluatedBody).getOrElse(
            throw new IllegalStateException(s"Function '${function.name}' did not return a Value")
        )

        require(
            function.returnType == "Value" || functionResult.t == function.returnType || functionResult.base_type_name() == function.returnType,
            s"Function '${function.name}' declared ${function.returnType} but returned ${functionResult.t}"
        )

        this.scopes = this.scopes.dropRight(1)
        this.returned = outerReturned
        this.has_returned = outerHasReturned
        functionResult

    def evaluate_node(node: SemanticNode, expected: Option[Value] = None): Value =
        node match
            case ProgramNode(statements) =>
                this.evaluate_program(ProgramNode(statements)).getOrElse(
                    throw new IllegalStateException("Program did not evaluate a Value")
                )
            case VariableNode(name) =>
                // Variable names have already been mapped to Value references by the FST.
                this.find_value(name)
            case MemberAccessNode(value, member) =>
                // Here the last hierarchically visited variable node can be known to reference the . or member access
                // MemberName = node.memberName
                // MemberAccess = args[VariableName][MemberName], from here we have the internal value where we can then set values using the Value Class as it is normally done.
                // Member access keeps returning a Value view backed by the original argument memory.
                this.evaluate_node(value).reference_member(member)
            case IndexAccessNode(value, StringLiteralNode(member)) =>
                this.evaluate_node(value).reference_member(member)
            case IndexAccessNode(value, index) =>
                // Fill in the gaps here
                // Index access uses the current hierarchical Value view, not a flattened resolver.
                this.evaluate_node(value).reference_element(Array(this.index_coordinate(index)))
            case MultiIndexAccessNode(value, indices) =>
                val coordinates = Array.ofDim[Int](indices.length)
                var index = 0
                while index < indices.length do
                    coordinates(index) = this.index_coordinate(indices(index))
                    index += 1
                this.evaluate_node(value).reference_element(coordinates)
            case NumericLiteralNode(value) =>
                val expectedValue = expected.getOrElse(
                    throw new IllegalArgumentException("A numeric literal requires a target or operand Value to provide its type registry")
                )
                expectedValue.registry.caster.cast(expectedValue.base_type_name(), value)
            case StringLiteralNode(value) =>
                throw new UnsupportedOperationException(s"No contextual string literal operator is registered for '$value'")
            case UnaryOperatorNode(operator, value) =>
                val evaluatedValue = this.evaluate_node(value, expected)
                operator match
                    case "+" => evaluatedValue.operator("unary+")()
                    case "-" => evaluatedValue.operator("unary-")()
                    case "!" => evaluatedValue.operator("!")()
                    case _ => throw new IllegalArgumentException(s"Unknown unary operator: $operator")
            case BinaryOperatorNode(left, operator, right) =>
                val leftValue = this.evaluate_node(left, expected)

                // These preserve short-circuit behavior while the actual operation remains a Value operator.
                if operator == "&&" && !this.condition_is_true(leftValue) then leftValue
                else if operator == "||" && this.condition_is_true(leftValue) then leftValue
                else leftValue.operator(operator)(this.evaluate_node(right, Some(leftValue)))
            case TernaryOperatorNode(condition, whenTrue, whenFalse) =>
                val conditionValue = this.evaluate_node(condition)
                if this.condition_is_true(conditionValue) then this.evaluate_node(whenTrue, expected)
                else this.evaluate_node(whenFalse, expected)
            case AssignmentOperatorNode(target, operator, assignedValue) =>
                val targetValue = this.evaluate_node(target)
                val rightValue = this.evaluate_node(assignedValue, Some(targetValue))
                targetValue.operator(operator)(rightValue)
            case DeclarationNode(valueType, name, initialValue) =>
                val stackValue = this.declared_value(name, valueType)
                initialValue.foreach(value => stackValue.operator("=")(this.evaluate_node(value, Some(stackValue))))
                this.store_value(name, stackValue)
                stackValue
            case BlockNode(statements) =>
                this.evaluate_block(BlockNode(statements)).getOrElse(
                    throw new IllegalStateException("Block did not evaluate a Value")
                )
            case IfNode(condition, thenBranch, elseBranch) =>
                val conditionValue = this.evaluate_node(condition)
                if this.condition_is_true(conditionValue) then this.evaluate_block(thenBranch).getOrElse(conditionValue)
                else
                    elseBranch match
                        case Some(branch) => this.evaluate_block(branch).getOrElse(conditionValue)
                        case None => conditionValue
            case WhileNode(condition, body) =>
                var conditionValue = this.evaluate_node(condition)
                var evaluatedValue: Value = conditionValue
                while !this.has_returned && this.condition_is_true(conditionValue) do
                    evaluatedValue = this.evaluate_block(body).getOrElse(conditionValue)
                    if !this.has_returned then conditionValue = this.evaluate_node(condition)
                evaluatedValue
            case ForNode(initializer, condition, update, body) =>
                var evaluatedValue = initializer.map(value => this.evaluate_node(value)).orElse(condition.map(value => this.evaluate_node(value))).getOrElse(
                    throw new IllegalStateException("A for loop requires an initializer or condition Value")
                )
                var continue = condition.forall(value => this.condition_is_true(this.evaluate_node(value)))
                while !this.has_returned && continue do
                    evaluatedValue = this.evaluate_block(body).getOrElse(evaluatedValue)
                    if !this.has_returned then
                        update.foreach(value => evaluatedValue = this.evaluate_node(value))
                        continue = condition.forall(value => this.condition_is_true(this.evaluate_node(value)))
                evaluatedValue
            case ReturnNode(value) =>
                val returnedValue = value.map(value => this.evaluate_node(value, expected)).getOrElse(
                    throw new UnsupportedOperationException("A void return does not contain a Value")
                )
                this.returned = Some(returnedValue)
                this.has_returned = true
                returnedValue
            case FunctionCallNode(MemberAccessNode(value, "operator"), arguments) =>
                val receiver = this.evaluate_node(value)
                require(arguments.nonEmpty, "Value.operator requires an operator name")
                val operatorName = arguments.head match
                    case StringLiteralNode(name) => name
                    case _ => throw new IllegalArgumentException("Value.operator requires a string operator name as its first argument")

                var evaluatedArguments: Vector[Value] = Vector.empty
                var argumentIndex = 1
                while argumentIndex < arguments.length do
                    evaluatedArguments = evaluatedArguments :+ this.evaluate_node(arguments(argumentIndex), Some(receiver))
                    argumentIndex += 1
                receiver.operator(operatorName)(evaluatedArguments*)
            case FunctionCallNode(MemberAccessNode(value, member), arguments) =>
                val receiver = this.evaluate_node(value)
                var evaluatedArguments: Vector[Value] = Vector.empty
                var argumentIndex = 0
                while argumentIndex < arguments.length do
                    evaluatedArguments = evaluatedArguments :+ this.evaluate_node(arguments(argumentIndex), Some(receiver))
                    argumentIndex += 1
                receiver.operator(member)(evaluatedArguments*)
            case FunctionCallNode(VariableNode(name), arguments) if this.tree.functions.contains(name) =>
                var evaluatedArguments: Vector[Value] = Vector.empty
                var argumentIndex = 0
                while argumentIndex < arguments.length do
                    evaluatedArguments = evaluatedArguments :+ this.evaluate_node(arguments(argumentIndex))
                    argumentIndex += 1
                this.evaluate_function(this.tree.functions(name), evaluatedArguments)
            case FunctionCallNode(function, arguments) =>
                val callable = this.evaluate_node(function)
                var evaluatedArguments: Vector[Value] = Vector.empty
                var argumentIndex = 0
                while argumentIndex < arguments.length do
                    evaluatedArguments = evaluatedArguments :+ this.evaluate_node(arguments(argumentIndex), Some(callable))
                    argumentIndex += 1
                callable.operator("call")(evaluatedArguments*)
            case function: FunctionDeclarationNode =>
                this.tree.functions(function.name) = function
                throw new IllegalStateException(s"Function declaration '${function.name}' is not an executable Value")
