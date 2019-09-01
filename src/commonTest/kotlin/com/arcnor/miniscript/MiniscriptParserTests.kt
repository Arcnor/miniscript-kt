package com.arcnor.miniscript

import kotlin.test.Test

class MiniscriptParserTests {
	@Test fun testPi() { testValidParse("pi < 4", true) }
	@Test fun testPi2() { testValidParse("(pi < 4)", true) }
	@Test fun testIf() { testValidParse("if true then 20 else 30", true) }
	@Test fun testFuncAssign() { testValidParse("f = function(x)\nreturn x*3\nend function\nf(14)", true) }
	@Test fun testIndexes() { testValidParse("foo=\"bar\"\nindexes(foo*2)\nfoo.indexes", true) }
	@Test fun testPush() { testValidParse("x=[]\nx.push(42)", true) }
	@Test fun testList() { testValidParse("list1=[10, 20, 30, 40, 50]; range(0, list1.len)", true) }
	@Test fun testFunc2() { testValidParse("f = function(x); print(\"foo\"); end function; print(false and f)", true) }
	@Test fun test42() { testValidParse("print 42", true) }
	@Test fun testTrue() { testValidParse("print true", true) }
	@Test fun testFuncCall() { testValidParse("f = function(x)\nprint x\nend function\nf 42", true) }
	@Test fun testListNulls() { testValidParse("myList = [1, null, 3]", true) }
	@Test fun testWhile() { testValidParse("while true; if true then; break; else; print 1; end if; end while", true) }
	@Test fun testNewline() { testValidParse("x = 0 or\n1", true) }
	@Test fun testNewlineArr() { testValidParse("x = [1, 2, \n 3]", true) }
	@Test fun testRange() { testValidParse("range 1,\n10, 2", true) }

	companion object {
		fun testValidParse(src: String, dumpTac: Boolean = false) {
			val parser = Parser();
			parser.Parse(src);
			if (dumpTac && parser.output != null) TAC.Dump(parser.output.code);
		}
	}
}