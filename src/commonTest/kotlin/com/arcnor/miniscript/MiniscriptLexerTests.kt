package com.arcnor.miniscript

import com.arcnor.miniscript.MiniscriptLexer.Lexer.Companion.LastToken
import kotlin.test.Test
import com.arcnor.miniscript.MiniscriptLexer.Token

class MiniscriptLexerTests {
	@Test
	fun testSimple() {
		val lex = MiniscriptLexer.Lexer("42  * 3.14158");

		Check(lex.Dequeue(), Token.Type.Number, "42")
		CheckLineNum(lex.lineNum, 1)
		Check(lex.Dequeue(), Token.Type.OpTimes)
		Check(lex.Dequeue(), Token.Type.Number, "3.14158")
		UnitTest.ErrorIf(!lex.AtEnd, "AtEnd not set when it should be")
		CheckLineNum(lex.lineNum, 1)
	}

	@Test
	fun testSimple2() {
		val lex = MiniscriptLexer.Lexer("6*(.1-foo) end if // and a comment!");
		Check(lex.Dequeue(), Token.Type.Number, "6");
		CheckLineNum(lex.lineNum, 1);
		Check(lex.Dequeue(), Token.Type.OpTimes);
		Check(lex.Dequeue(), Token.Type.LParen);
		Check(lex.Dequeue(), Token.Type.Number, ".1");
		Check(lex.Dequeue(), Token.Type.OpMinus);
		Check(lex.Peek(), Token.Type.Identifier, "foo");
		Check(lex.Peek(), Token.Type.Identifier, "foo");
		Check(lex.Dequeue(), Token.Type.Identifier, "foo");
		Check(lex.Dequeue(), Token.Type.RParen);
		Check(lex.Dequeue(), Token.Type.Keyword, "end if");
		Check(lex.Dequeue(), Token.Type.EOL);
		UnitTest.ErrorIf(!lex.AtEnd, "AtEnd not set when it should be");
		CheckLineNum(lex.lineNum, 1);
	}

	@Test
	fun testSimple3() {
		val lex = MiniscriptLexer.Lexer("\"foo\" \"isn't \"\"real\"\"\" \"now \"\"\"\" double!\"");
		Check(lex.Dequeue(), Token.Type.String, "foo");
		Check(lex.Dequeue(), Token.Type.String, "isn't \"real\"");
		Check(lex.Dequeue(), Token.Type.String, "now \"\" double!");
		UnitTest.ErrorIf(!lex.AtEnd, "AtEnd not set when it should be");
	}

	@Test
	fun testSimple4() {
		val lex = MiniscriptLexer.Lexer("foo\nbar\rbaz\r\nbamf");
		Check(lex.Dequeue(), Token.Type.Identifier, "foo");
		CheckLineNum(lex.lineNum, 1);
		Check(lex.Dequeue(), Token.Type.EOL);
		Check(lex.Dequeue(), Token.Type.Identifier, "bar");
		CheckLineNum(lex.lineNum, 2);
		Check(lex.Dequeue(), Token.Type.EOL);
		Check(lex.Dequeue(), Token.Type.Identifier, "baz");
		CheckLineNum(lex.lineNum, 3);
		Check(lex.Dequeue(), Token.Type.EOL);
		Check(lex.Dequeue(), Token.Type.Identifier, "bamf");
		CheckLineNum(lex.lineNum, 4);
		Check(lex.Dequeue(), Token.Type.EOL);
		UnitTest.ErrorIf(!lex.AtEnd, "AtEnd not set when it should be");
	}

	// TODO: This test is not a good one, we're testing internals directly...
	@Test
	fun testLastToken() {
		Check(LastToken("x=42 // foo"), Token.Type.Number, "42");
		Check(LastToken("x = [1, 2, // foo"), Token.Type.Comma);
		Check(LastToken("x = [1, 2 // foo"), Token.Type.Number, "2");
		Check(LastToken("x = [1, 2 // foo // and \"more\" foo"), Token.Type.Number, "2");
		Check(LastToken("x = [\"foo\", \"//bar\"]"), Token.Type.RSquare);
		Check(LastToken("print 1 // line 1\nprint 2"), Token.Type.Number, "2");
		Check(LastToken("print \"Hi\"\"Quote\" // foo bar"), Token.Type.String, "Hi\"Quote");
	}

	private fun Check(tok: Token?, type: Token.Type, text: String? = null, lineNum: Int = 0) {
		UnitTest.ErrorIfNull(tok);
		if (tok == null) return;
		UnitTest.ErrorIf(
			tok.type != type,
			"Token type: expected " + type + ", but got " + tok.type
		);
		UnitTest.ErrorIf(
			text != null && tok.text != text,
			"Token text: expected " + text + ", but got " + tok.text
		);
	}

	private fun CheckLineNum(actual: Int, expected: Int) {
		UnitTest.ErrorIf(
			actual != expected, "Lexer line number: expected "
					+ expected + ", but got " + actual
		)
	}
}