package com.arcnor.miniscript

object Keywords {
	val all = arrayOf(
		"break",
		"continue",
		"else",
		"end",
		"for",
		"function",
		"if",
		"in",
		"isa",
		"new",
		"null",
		"then",
		"repeat",
		"return",
		"while",
		"and",
		"or",
		"not",
		"true",
		"false"
	)

	fun IsKeyword(text: String) = all.contains(text)
}