package engine.context.operation.prefabs

import engine.context.{Context, ContextDelta}
import engine.context.executor.ContextExecutionError
import engine.context.operation.ContextOperation

final case class ContextFailOperation(id: String, reason: String = "Intentional failure") extends ContextOperation:
  override def run(context: Context): Either[ContextExecutionError, ContextDelta] =
    Left(ContextExecutionError.OperationFailed(id, reason))
