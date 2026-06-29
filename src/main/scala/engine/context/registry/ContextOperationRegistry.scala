package engine.context.registry

import engine.context.operation.ContextOperation

final case class ContextOperationRegistry(
  operations: Map[String, ContextOperation] = Map.empty
):
  def register(operation: ContextOperation): Either[String, ContextOperationRegistry] =
    if operations.contains(operation.id) then Left(s"Operation already registered: ${operation.id}")
    else Right(copy(operations = operations + (operation.id -> operation)))

  def registerUnsafe(operation: ContextOperation): ContextOperationRegistry =
    copy(operations = operations + (operation.id -> operation))

  def get(id: String): Option[ContextOperation] =
    operations.get(id)

object ContextOperationRegistry:
  val empty: ContextOperationRegistry = ContextOperationRegistry()
  def withOperations(ops: ContextOperation*): ContextOperationRegistry =
    ops.foldLeft(empty)((registry, op) => registry.registerUnsafe(op))
