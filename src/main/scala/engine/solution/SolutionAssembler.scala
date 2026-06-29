package engine.solution

/** Alters solution-operation topology without altering the problem context description. */
trait SolutionAssembler:
  def append(solutionStrategy: SolutionStrategy, operation: SolutionOperation): SolutionStrategy =
    solutionStrategy.copy(operations = solutionStrategy.operations :+ operation)

  def replaceOperation(
    solutionStrategy: SolutionStrategy,
    operationId: String,
    replacement: SolutionOperation
  ): SolutionStrategy =
    solutionStrategy.copy(operations = solutionStrategy.operations.map {
      case operation if operation.id == operationId => replacement
      case operation => replaceInChildren(operation, operationId, replacement)
    })

  private def replaceInChildren(
    operation: SolutionOperation,
    operationId: String,
    replacement: SolutionOperation
  ): SolutionOperation =
    val replacedChildren = operation.children.map {
      case child if child.id == operationId => replacement
      case child => replaceInChildren(child, operationId, replacement)
    }
    operation match
      case instruction: SolutionInstruction => instruction.copy(children = replacedChildren)
      case control: SolutionControl => control.copy(children = replacedChildren)

object SolutionAssembler extends SolutionAssembler
