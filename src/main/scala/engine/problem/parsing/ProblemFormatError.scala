package engine.problem.parsing

final case class ProblemFormatError(message: String, path: String = "$", cause: Option[Throwable] = None):
  override def toString: String =
    if path == "$" then message else s"$message at $path"
