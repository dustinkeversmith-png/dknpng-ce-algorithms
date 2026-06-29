package engine.context.trace

object ContextTraceRecorder:
  def empty: ContextTrace = ContextTrace.empty
  def record(trace: ContextTrace, event: ContextTraceEvent): ContextTrace = trace.append(event)
