package engine.context.operation

import engine.context.{Context, ContextDelta}
import engine.context.executor.ContextExecutionError
import engine.context.operation.ContextOperationStep

final case class ContextReadFactStep(id: String, key: String) extends ContextOperationStep:
  override def description: String = s"Require fact '$key'"
  override def run(context: Context): Either[ContextExecutionError, ContextDelta] =
    context.fact(key) match
      case Some(_) => Right(ContextDelta.empty)
      case None => Left(ContextExecutionError.OperationFailed(id, s"Missing fact: $key"))

final case class ContextAddDeltaStep(
  id: String,
  description: String,
  deltaFactory: Context => Either[ContextExecutionError, ContextDelta]
) extends ContextOperationStep:
  override def run(context: Context): Either[ContextExecutionError, ContextDelta] = deltaFactory(context)
