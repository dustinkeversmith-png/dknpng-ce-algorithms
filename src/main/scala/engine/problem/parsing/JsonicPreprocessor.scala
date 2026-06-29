package engine.problem.parsing

/** Small JSONic convenience pass: removes JS-style comments and trailing commas. */
object JsonicPreprocessor:
  def normalize(input: String): String =
    removeTrailingCommas(removeComments(input))

  private def removeComments(input: String): String =
    val out = new StringBuilder(input.length)
    var i = 0
    var inString = false
    var escaped = false
    while i < input.length do
      val c = input.charAt(i)
      val next = if i + 1 < input.length then input.charAt(i + 1) else '\u0000'
      if inString then
        out.append(c)
        if escaped then escaped = false
        else if c == '\\' then escaped = true
        else if c == '"' then inString = false
        i += 1
      else if c == '"' then
        inString = true
        out.append(c)
        i += 1
      else if c == '/' && next == '/' then
        i += 2
        while i < input.length && input.charAt(i) != '\n' do i += 1
        if i < input.length then out.append('\n')
      else if c == '/' && next == '*' then
        i += 2
        while i + 1 < input.length && !(input.charAt(i) == '*' && input.charAt(i + 1) == '/') do i += 1
        i = math.min(i + 2, input.length)
      else
        out.append(c)
        i += 1
    out.toString

  private def removeTrailingCommas(input: String): String =
    val out = new StringBuilder(input.length)
    var i = 0
    var inString = false
    var escaped = false
    while i < input.length do
      val c = input.charAt(i)
      if inString then
        out.append(c)
        if escaped then escaped = false
        else if c == '\\' then escaped = true
        else if c == '"' then inString = false
        i += 1
      else if c == '"' then
        inString = true
        out.append(c)
        i += 1
      else if c == ',' then
        var j = i + 1
        while j < input.length && input.charAt(j).isWhitespace do j += 1
        if j < input.length && (input.charAt(j) == '}' || input.charAt(j) == ']') then i += 1
        else
          out.append(c)
          i += 1
      else
        out.append(c)
        i += 1
    out.toString
