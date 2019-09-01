package com.arcnor.miniscript

object UnitTest {
    fun ReportError(err: String) {
        // Set a breakpoint here if you want to drop into the debugger
        // on any unit test failure.
        println(err);
//        UnityEngine.Debug.LogError("Miniscript unit test failed: " + err);
    }

    fun ErrorIf(condition: Boolean, err: String) {
        if (condition) ReportError(err)
    }

    fun ErrorIfNull(obj: Any?) {
        if (obj == null) ReportError("Unexpected null");
    }

    fun ErrorIfNotNull(obj: Any?) {
        if (obj != null) ReportError("Expected null, but got non-null");
    }

    fun ErrorIfNotEqual(actual: String, expected: String, desc: String = "Expected $expected, got $actual") {
        if (actual == expected) return;
        ReportError(desc);
    }

    fun ErrorIfNotEqual(actual: Float, expected: Float,
    desc: String="Expected $expected, got $actual") {
        if (actual == expected) return;
        ReportError(desc);
    }

    fun Run() {
        MiniscriptLexer.Lexer.RunUnitTests();
//        Parser.RunUnitTests();
    }
}