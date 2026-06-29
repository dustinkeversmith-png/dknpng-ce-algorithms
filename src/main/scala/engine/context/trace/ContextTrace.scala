package engine.context.trace

final case class ContextTrace(events: Vector[ContextTraceEvent] = Vector.empty):
  def append(event: ContextTraceEvent): ContextTrace = copy(events = events :+ event)
  def ++(other: ContextTrace): ContextTrace = copy(events = events ++ other.events)
  def nonEmpty: Boolean = events.nonEmpty
  def size: Int = events.size

object ContextTrace:
  val empty: ContextTrace = ContextTrace()
