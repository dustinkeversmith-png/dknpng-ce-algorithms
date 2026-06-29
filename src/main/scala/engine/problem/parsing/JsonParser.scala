package engine.problem.parsing

import JsonValue.*

object JsonParser:
  def parseJsonic(input: String): Either[ProblemFormatError, JsonValue] =
    parse(JsonicPreprocessor.normalize(input))

  def parse(input: String): Either[ProblemFormatError, JsonValue] =
    val parser = Parser(input)
    parser.parseValue().flatMap { value =>
      parser.skipWhitespace()
      if parser.isAtEnd then Right(value)
      else Left(parser.error("Unexpected trailing input"))
    }

  private final class Parser(input: String):
    private var index = 0

    def isAtEnd: Boolean = index >= input.length

    def skipWhitespace(): Unit =
      while !isAtEnd && input.charAt(index).isWhitespace do index += 1

    def parseValue(): Either[ProblemFormatError, JsonValue] =
      skipWhitespace()
      if isAtEnd then Left(error("Expected JSON value"))
      else input.charAt(index) match
        case '{' => parseObject()
        case '[' => parseArray()
        case '"' => parseString().map(JsonString.apply)
        case 't' => parseLiteral("true", JsonBoolean(true))
        case 'f' => parseLiteral("false", JsonBoolean(false))
        case 'n' => parseLiteral("null", JsonNull)
        case '-' => parseNumber()
        case c if c.isDigit => parseNumber()
        case other => Left(error(s"Unexpected character '$other'"))

    private def parseObject(): Either[ProblemFormatError, JsonValue] =
      expect('{')
      skipWhitespace()
      var fields = Map.empty[String, JsonValue]
      if consume('}') then Right(JsonObject(fields))
      else
        var continue = true
        var failure: Option[ProblemFormatError] = None
        while continue && failure.isEmpty do
          skipWhitespace()
          if peek != Some('"') then failure = Some(error("Expected object key string"))
          else
            parseString() match
              case Left(err) => failure = Some(err)
              case Right(key) =>
                skipWhitespace()
                if !consume(':') then failure = Some(error("Expected ':' after object key"))
                else
                  parseValue() match
                    case Left(err) => failure = Some(err)
                    case Right(value) =>
                      fields = fields + (key -> value)
                      skipWhitespace()
                      if consume('}') then continue = false
                      else if consume(',') then continue = true
                      else failure = Some(error("Expected ',' or '}' in object"))
        failure.toLeft(JsonObject(fields))

    private def parseArray(): Either[ProblemFormatError, JsonValue] =
      expect('[')
      skipWhitespace()
      var values = Vector.empty[JsonValue]
      if consume(']') then Right(JsonArray(values))
      else
        var continue = true
        var failure: Option[ProblemFormatError] = None
        while continue && failure.isEmpty do
          parseValue() match
            case Left(err) => failure = Some(err)
            case Right(value) =>
              values = values :+ value
              skipWhitespace()
              if consume(']') then continue = false
              else if consume(',') then continue = true
              else failure = Some(error("Expected ',' or ']' in array"))
        failure.toLeft(JsonArray(values))

    private def parseString(): Either[ProblemFormatError, String] =
      if !consume('"') then Left(error("Expected string"))
      else
        val out = new StringBuilder
        var done = false
        var failure: Option[ProblemFormatError] = None
        while !done && failure.isEmpty do
          if isAtEnd then failure = Some(error("Unterminated string"))
          else
            val c = input.charAt(index)
            index += 1
            c match
              case '"' => done = true
              case '\\' =>
                if isAtEnd then failure = Some(error("Unterminated escape sequence"))
                else
                  val escaped = input.charAt(index)
                  index += 1
                  escaped match
                    case '"' => out.append('"')
                    case '\\' => out.append('\\')
                    case '/' => out.append('/')
                    case 'b' => out.append('\b')
                    case 'f' => out.append('\f')
                    case 'n' => out.append('\n')
                    case 'r' => out.append('\r')
                    case 't' => out.append('\t')
                    case 'u' =>
                      if index + 4 > input.length then failure = Some(error("Incomplete unicode escape"))
                      else
                        val hex = input.substring(index, index + 4)
                        if hex.forall(ch => ch.isDigit || "abcdefABCDEF".contains(ch)) then
                          out.append(Integer.parseInt(hex, 16).toChar)
                          index += 4
                        else failure = Some(error("Invalid unicode escape"))
                    case other => failure = Some(error(s"Invalid escape sequence \\$other"))
              case other => out.append(other)
        failure.toLeft(out.toString)

    private def parseNumber(): Either[ProblemFormatError, JsonValue] =
      val start = index
      if consume('-') then ()
      consumeDigits()
      if consume('.') then consumeDigits()
      if peek.exists(c => c == 'e' || c == 'E') then
        index += 1
        if peek.exists(c => c == '+' || c == '-') then index += 1
        consumeDigits()
      val raw = input.substring(start, index)
      
      try BigDecimal(raw).toDouble match
        case d if d.isWhole => Right(JsonNumber(d.toLong))
        case d => Right(JsonNumber(d))
      catch
        case _: NumberFormatException => Left(error(s"Invalid number '$raw'"))



    private def consumeDigits(): Unit =
      while !isAtEnd && input.charAt(index).isDigit do index += 1

    private def parseLiteral[A <: JsonValue](literal: String, value: A): Either[ProblemFormatError, JsonValue] =
      if input.startsWith(literal, index) then
        index += literal.length
        Right(value)
      else Left(error(s"Expected '$literal'"))

    private def peek: Option[Char] = if isAtEnd then None else Some(input.charAt(index))

    private def consume(c: Char): Boolean =
      if !isAtEnd && input.charAt(index) == c then
        index += 1
        true
      else false

    private def expect(c: Char): Unit =
      val _ = consume(c)

    def error(message: String, cause: Option[Throwable] = None): ProblemFormatError =
      ProblemFormatError(message, s"$$[$index]", cause)
