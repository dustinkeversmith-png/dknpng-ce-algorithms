package engine.context.operation.prefabs

import engine.context.{Context, ContextDelta}
import engine.context.executor.ContextExecutionError
import engine.context.operation.ContextOperation

final case class ContextResolveUnknownOperation(id: String, unknownKey: String, factKey: String) extends ContextOperation:
  override def description: String = s"Resolve unknown $unknownKey into fact $factKey"
  override def run(context: Context): Either[ContextExecutionError, ContextDelta] =
    context.unknowns.get(unknownKey) match
      case Some(value) =>
        Right(ContextDelta(
          addFacts = Map(factKey -> value),
          removeUnknowns = Set(unknownKey)
        ))
      case None =>
        Left(ContextExecutionError.OperationFailed(id, s"Unknown not found: $unknownKey"))
