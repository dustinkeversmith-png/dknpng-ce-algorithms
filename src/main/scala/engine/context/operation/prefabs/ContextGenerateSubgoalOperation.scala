package engine.context.operation.prefabs

import engine.context.{Context, ContextDelta}
import engine.context.executor.ContextExecutionError
import engine.context.operation.ContextOperation

final case class ContextGenerateSubgoalOperation(id: String, goal: String) extends ContextOperation:
  override def description: String = s"Generate subgoal: $goal"
  override def run(context: Context): Either[ContextExecutionError, ContextDelta] =
    Right(ContextDelta(addGoals = Vector(goal)))
