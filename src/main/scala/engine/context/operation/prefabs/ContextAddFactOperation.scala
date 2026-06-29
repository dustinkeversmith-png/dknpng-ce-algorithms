package engine.context.operation.prefabs

import engine.context.{ContextValue, Context, ContextDelta}
import engine.context.executor.ContextExecutionError
import engine.context.operation.ContextOperation

final case class ContextAddFactOperation(id: String, key: String, value: ContextValue) extends ContextOperation:
  override def description: String = s"Add fact $key"
  override def run(context: Context): Either[ContextExecutionError, ContextDelta] =
    Right(ContextDelta(addFacts = Map(key -> value)))
