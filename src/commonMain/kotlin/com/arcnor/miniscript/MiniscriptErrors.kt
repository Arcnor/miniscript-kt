package com.arcnor.miniscript

import kotlin.reflect.KClass
import kotlin.reflect.typeOf

data class SourceLoc(
	// file name, etc. (optional)
	val context: String?,
	val lineNum: Int) {

	override fun toString() = "[${context?.plus(" ") ?: ""}line $lineNum]"
}

open class MiniscriptException(var location: SourceLoc?, message: String?, cause: Throwable?): Exception(message) {
	constructor(message: String?) : this(null, message, null) {
	}

	constructor(context: String?, lineNum: Int, message: String?) : this(SourceLoc(context, lineNum), message, null)

	constructor(message: String, cause: Throwable?) : this(null, message, cause)

	/// <summary>
	/// Get a standard description of this error, including type and location.
	/// </summary>
	fun Description(): String {
		var desc = "Error: ";
		when {
			this is LexerException -> desc = "Lexer Error: "
			this is CompilerException -> desc = "Compiler Error: "
			this is MSRuntimeException -> desc = "Runtime Error: "
		};
		desc += message;
		if (location != null) desc += " $location";
		return desc;
	}

}

class LexerException(location: SourceLoc?, message: String?, cause: Throwable?) : MiniscriptException(location, message, cause) {
	constructor(): this(null, "Lexer Error", null)
	constructor(message: String) : this(null, message, null)
}

class CompilerException(location: SourceLoc?, message: String?, cause: Throwable?) : MiniscriptException(location, message, cause) {
	constructor(): this(null, "Syntax Error", null)
	constructor(message: String) : this(null, message, null)
	constructor(context: String?, lineNum: Int, message: String?) : this(SourceLoc(context, lineNum), message, null)
}
open class MSRuntimeException(location: SourceLoc?, message: String?, cause: Throwable?) : MiniscriptException(location, message, cause) {
	constructor(): this(null, "Runtime Error", null)
	constructor(message: String) : this(null, message, null)
}
class IndexException(location: SourceLoc?, message: String?, cause: Throwable?) : MSRuntimeException(location, message, cause) {
	constructor(): this(null, "Index Error (index out of range)", null)
	constructor(message: String) : this(null, message, null)
}
class KeyException(location: SourceLoc?, message: String?, cause: Throwable?) : MSRuntimeException(location, message, cause) {
	constructor(key: String): this(null, "Key Not Found: '$key' not found in map", null)
}
class TypeException(location: SourceLoc?, message: String?, cause: Throwable?) : MSRuntimeException(location, message, cause) {
	constructor() : this(null, "Type Error (wrong type for whatever you're doing)", null)
	constructor(message: String) : this(null, message, null)
	constructor(message: String, cause: Throwable) : this(null, message, cause)
}
class TooManyArgumentsException(location: SourceLoc?, message: String?, cause: Throwable?) : MSRuntimeException(location, message, cause) {
	constructor() : this(null, "Too Many Arguments", null)
	constructor(message: String) : this(null, message, null)
}
class LimitExceededException(location: SourceLoc?, message: String?, cause: Throwable?) : MSRuntimeException(location, message, cause) {
	constructor() : this(null, "Runtime Limit Exceeded", null)
	constructor(message: String) : this(null, message, null)
	constructor(message: String, cause: Throwable) : this(null, message, cause)
}
class UndefinedIdentifierException(location: SourceLoc?, message: String?, cause: Throwable?) : MSRuntimeException(location, message, cause) {
	constructor(ident: String): this(null, "Undefined Identifier: '$ident' is unknown in this context", null)
}

object Check {
	fun Range(i: Int, min: Int, max: Int, desc: String = "index") {
		if (i < min || i > max) {
			throw IndexException("Index Error: $desc ($i) out of range ($min to $max)")
		}
	}

	/*fun Type(value: Value?, requiredType: KClass<out Any>, desc: String? = null) {
		if (!requiredType.isInstance(value)) {
			// FIXME: Type is not being found
			throw TypeException("got a ${"TODO: Get Type"} where a ${requiredType.simpleName} was required${if (desc != null) { " ($desc)"} else ""}")
		}
	}*/
}