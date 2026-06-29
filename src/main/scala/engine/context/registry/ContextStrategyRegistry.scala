package engine.context.registry

import engine.context.strategy.{ContextStrategy, ContextStrategyId}

final case class ContextStrategyRegistry(
  strategies: Map[ContextStrategyId, ContextStrategy] = Map.empty
):
  def register(ContextStrategy: ContextStrategy): Either[String, ContextStrategyRegistry] =
    if strategies.contains(ContextStrategy.id) then Left(s"ContextStrategy already registered: ${ContextStrategy.id.value}")
    else Right(copy(strategies = strategies + (ContextStrategy.id -> ContextStrategy)))

  def registerUnsafe(ContextStrategy: ContextStrategy): ContextStrategyRegistry =
    copy(strategies = strategies + (ContextStrategy.id -> ContextStrategy))

  def get(id: ContextStrategyId): Option[ContextStrategy] = strategies.get(id)

object ContextStrategyRegistry:
  val empty: ContextStrategyRegistry = ContextStrategyRegistry()
