package com.arcnor.miniscript

class MiniscriptLexer {
	class Token(
		var type: Type = Type.Unknown,
		// may be null for things like operators, whose text is fixed
		var text: String? = null
	) {
		enum class Type {
			Unknown,
			Keyword,
			Number,
			String,
			Identifier,
			OpAssign,
			OpPlus,
			OpMinus,
			OpTimes,
			OpDivide,
			OpMod,
			OpPower,
			OpEqual,
			OpNotEqual,
			OpGreater,
			OpGreatEqual,
			OpLesser,
			OpLessEqual,
			LParen,
			RParen,
			LSquare,
			RSquare,
			LCurly,
			RCurly,
			AddressOf,
			Comma,
			Dot,
			Colon,
			Comment,
			EOL
		}

		var afterSpace: Boolean = false

		override fun toString(): String {
			if (text == null) return type.toString();
			return "$type($text)";
		}

		companion object {
			val EOL: Token = Token(type = Type.EOL)
		}
	}

	class Lexer(private val input: String) {
		var lineNum = 1    // start at 1, so we report 1-based line numbers
		private val inputLength = input.length;
		private var position = 0;
		private var pending = mutableListOf<Token>()

		val AtEnd: Boolean
			get() = position >= inputLength && pending.isEmpty()

		fun Peek(): Token {
			if (pending.isEmpty()) {
				if (AtEnd) return Token.EOL
				pending.add(Dequeue())
			}
			return pending[0]
		}

		fun Dequeue(): Token {
			if (pending.isNotEmpty()) return pending.removeAt(0);

			val oldPos = position;
			SkipWhitespaceAndComment();

			if (AtEnd) return Token.EOL;

			var result = Token();
			result.afterSpace = (position > oldPos);
			var startPos = position;
			var c = input[position++];

			// Handle two-character operators first.
			if (!AtEnd) {
				val c2 = input [position];
				if (c == '=' && c2 == '=') result.type = Token.Type.OpEqual;
				if (c == '!' && c2 == '=') result.type = Token.Type.OpNotEqual;
				if (c == '>' && c2 == '=') result.type = Token.Type.OpGreatEqual;
				if (c == '<' && c2 == '=') result.type = Token.Type.OpLessEqual;

				if (result.type != Token.Type.Unknown) {
					position++;
					return result;
				}
			}

			// Then, handle more extended tokens.
			result.type = when (c) {
				'+' -> Token.Type.OpPlus
				'-' -> Token.Type.OpMinus
				'*' -> Token.Type.OpTimes
				'/' -> Token.Type.OpDivide
				'%' -> Token.Type.OpMod
				'^' -> Token.Type.OpPower
				'(' -> Token.Type.LParen
				')' -> Token.Type.RParen
				'[' -> Token.Type.LSquare
				']' -> Token.Type.RSquare
				'{' -> Token.Type.LCurly
				'}' -> Token.Type.RCurly
				',' -> Token.Type.Comma
				':' -> Token.Type.Colon
				'=' -> Token.Type.OpAssign
				'<' -> Token.Type.OpLesser
				'>' -> Token.Type.OpGreater
				'@' -> Token.Type.AddressOf
				';', '\n' -> {
					result.text = if (c == ';') ";" else "\n"
					if (c != ';') lineNum++;

					Token.Type.EOL;
				}
				'\r' -> {
					// Careful; DOS may use \r\n, so we need to check for that too.
					if (position < inputLength && input[position] == '\n') {
						position++;
						result.text = "\r\n";
					} else {
						result.text = "\r";
					}
					lineNum++;

					Token.Type.EOL;
				}
				else -> {
					Token.Type.Unknown
				}
			}
			if (result.type != Token.Type.Unknown) return result;

			// Then, handle more extended tokens.

			if (c == '.') {
				// A token that starts with a dot is just Type.Dot, UNLESS
				// it is followed by a number, in which case it's a decimal number.
				if (position >= inputLength || !IsNumeric(input[position])) {
					result.type = Token.Type.Dot;
					return result;
				}
			}

			if (c == '.' || IsNumeric(c)) {
				result.type = Token.Type.Number;
				while (position < inputLength) {
					val lastc = c;
					c = input[position];
					if (IsNumeric(c) || c == '.' || c == 'E' || c == 'e' ||
						(c == '-' && (lastc == 'E' || lastc == 'e'))) {
						position++;
					} else break;
				}
			} else if (IsIdentifier(c)) {
				while (position < inputLength) {
					if (IsIdentifier(input[position])) position++;
					else break;
				}
				result.text = input.substring(startPos, position);
				result.type = (if (Keywords.IsKeyword(result.text!!)) Token.Type.Keyword else Token.Type.Identifier);
				if (result.text == "end") {
					// As a special case: when we see "end", grab the next keyword (after whitespace)
					// too, and conjoin it, so our token is "end if", "end function", etc.
					val nextWord = Dequeue();
					if (nextWord != null && nextWord.type == Token.Type.Keyword) {
						result.text = result.text + " " + nextWord.text;
					} else {
						// Oops, didn't find another keyword.  User error.
						throw LexerException("'end' without following keyword ('if', 'function', etc.)");
					}
				} else if (result.text == "else") {
					// And similarly, conjoin an "if" after "else" (to make "else if").
					val p = position;
					val nextWord = Dequeue();
					if (nextWord != null && nextWord.text == "if") result.text = "else if";
					else position = p;
				}
				return result;
			} else if (c == '"') {
				// Lex a string... to the closing ", but skipping (and singling) a doubled double quote ("")
				result.type = Token.Type.String;
				var haveDoubledQuotes = false;
				startPos = position;
				var gotEndQuote = false;
				while (position < inputLength) {
					c = input[position++];
					if (c == '"') {
						if (position < inputLength && input[position] == '"') {
							// This is just a doubled quote.
							haveDoubledQuotes = true;
							position++;
						} else {
							// This is the closing quote, marking the end of the string.
							gotEndQuote = true;
							break;
						}
					}
				}
				if (!gotEndQuote) throw LexerException("missing closing quote (\")");
				result.text = input.substring(startPos, position-1);
				if (haveDoubledQuotes) result.text = result.text!!.replace("\"\"", "\"");
				return result;
			} else {
				result.type = Token.Type.Unknown;
			}

			result.text = input.substring(startPos, position);
			return result;
		}

		private fun SkipWhitespaceAndComment() {
			while (!AtEnd && IsWhitespace(input[position])) {
				position++
			}

			if (position < input.length - 1 && input[position] === '/' && input[position + 1] === '/') {
				// Comment.  Skip to end of line.
				position += 2
				while (!AtEnd && input[position] !== '\n') position++
			}
		}

		/**
		 * Caution: ignores queue, and uses only current position
		 */
		fun IsAtWhitespace() = AtEnd || IsWhitespace(input[position])

		companion object {
			fun IsNumeric(c: Char) = c in '0'..'9'

			fun IsIdentifier(c: Char) = (c == '_'
					|| c in 'a'..'z'
					|| c in 'A'..'Z'
					|| c in '0'..'9'
					|| c > '\u009F')

			fun IsWhitespace(c: Char) = c == ' ' || c == '\t'

			fun IsInStringLiteral(charPos: Int, source: String, startPos: Int = 0): Boolean {
				var inString = false;
				for (i in startPos until charPos) {
					if (source[i] == '"') inString = !inString;
				}
				return inString;
			}

			fun CommentStartPos(source: String, startPos: Int): Int {
				// Find the first occurrence of "//" in this line that
				// is not within a string literal.
				var commentStart = startPos - 2
				while (true) {
					commentStart = source.indexOf("//", commentStart + 2)
					if (commentStart < 0) break    // no comment found
					if (!IsInStringLiteral(commentStart, source, startPos)) break    // valid comment
				}
				return commentStart
			}

			fun TrimComment(source: String): String {
				val startPos = source.lastIndexOf('\n') + 1
				val commentStart = CommentStartPos(source, startPos)
				// TODO: Check if the trimming is being done properly
				return if (commentStart >= 0) source.substring(startPos, commentStart) else source
			}

			// Find the last token in the given source, ignoring any whitespace
			// or comment at the end of that line.
			fun LastToken(source: String): Token {
				// Start by finding the start and logical  end of the last line.
				val startPos = source.lastIndexOf('\n') + 1
				val commentStart = CommentStartPos(source, startPos)

				// Walk back from end of string or start of comment, skipping whitespace.
				var endPos = if (commentStart >= 0) commentStart - 1 else source.length - 1
				while (endPos >= 0 && IsWhitespace(source[endPos])) endPos--
				if (endPos < 0) return Token.EOL

				// Find the start of that last token.
				// There are several cases to consider here.
				var tokStart = endPos
				val c = source[endPos]
				if (IsIdentifier(c)) {
					while (tokStart > startPos && IsIdentifier(source[tokStart - 1])) tokStart--
				} else if (c == '"') {
					var inQuote = true
					while (tokStart > startPos) {
						tokStart--
						if (source[tokStart] === '"') {
							inQuote = !inQuote
							if (!inQuote && tokStart > startPos && source[tokStart - 1] !== '"') break
						}
					}
				} else if (c == '=' && tokStart > startPos) {
					val c2 = source[tokStart - 1]
					if (c2 == '>' || c2 == '<' || c2 == '=' || c2 == '!') tokStart--
				}

				// Now use the standard lexer to grab just that bit.
				val lex = Lexer(source)
				lex.position = tokStart
				return lex.Dequeue()
			}


			fun RunUnitTests() {

			}
		}
	}
}