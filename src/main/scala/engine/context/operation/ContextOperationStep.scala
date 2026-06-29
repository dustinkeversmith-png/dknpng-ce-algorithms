package engine.context.operation

import engine.context.{Context, ContextDelta}
import engine.context.executor.ContextExecutionError

/** One inspectable unit inside an operation plan. */
trait ContextOperationStep:
  def id: String
  def description: String
  def run(context: Context): Either[ContextExecutionError, ContextDelta]
