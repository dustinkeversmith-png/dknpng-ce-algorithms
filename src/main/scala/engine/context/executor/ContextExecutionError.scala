package engine.context.executor

sealed trait ContextExecutionError:
  def message: String

object ContextExecutionError:
  final case class OperationNotFound(operationId: String) extends ContextExecutionError:
    def message: String = s"Operation not found: $operationId"

  final case class OperationFailed(operationId: String, reason: String) extends ContextExecutionError:
    def message: String = s"Operation failed: $operationId. $reason"

  final case class UnsupportedComposition(mode: String) extends ContextExecutionError:
    def message: String = s"Unsupported composition mode: $mode"

  final case class BudgetExceeded(message: String) extends ContextExecutionError
  final case class DepthExceeded(message: String) extends ContextExecutionError
  final case class EmptyComposite(message: String) extends ContextExecutionError
