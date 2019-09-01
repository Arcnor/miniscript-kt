package com.arcnor.miniscript

import kotlin.math.*
import kotlin.random.Random


/**
This file defines the Intrinsic class, which represents a built-in function
available to MiniScript code.  All intrinsics are held in static storage, so
this class includes static functions such as GetByName to look up
already-defined intrinsics.  See Chapter 2 of the MiniScript Integration
Guide for details on adding your own intrinsics.

This file also contains the Intrinsics static class, where all of the standard
intrinsics are defined.  This is initialized automatically, so normally you
don’t need to worry about it, though it is a good place to look for examples
of how to write intrinsic functions.

Note that you should put any intrinsics you add in a separate file; leave the
MiniScript source files untouched, so you can easily replace them when updates
become available.
 */

/// <summary>
/// IntrinsicCode is a delegate to the actual C# code invoked by an intrinsic method.
/// </summary>
/// <param name="context">TAC.Context in which the intrinsic was invoked</param>
/// <param name="partialResult">partial result from a previous invocation, if any</param>
/// <returns>result of the computation: whether it's complete, a partial result if not, and a Value if so</returns>
//public delegate Intrinsic.Result IntrinsicCode(TAC.Context context, Intrinsic.Result partialResult);

typealias IntrinsicCode = (context: TAC.Context, partialResult: Intrinsic.Result?) -> Intrinsic.Result

/// <summary>
/// Information about the app hosting MiniScript.  Set this in your main program.
/// This is provided to the user via the `version` intrinsic.
/// </summary>
object HostInfo {
	/**
	 * name of the host program
	 */
	var name: String? = null
	/**
	 * URL or other short info about the host
	 */
	var info: String? = null
	/**
	 * host program version number
	 */
	var version: Double = 0.toDouble()
}

/// <summary>
/// Intrinsic: represents an intrinsic function available to MiniScript code.
/// </summary>
class Intrinsic(
	// name of this intrinsic (should be a valid MiniScript identifier)
	val name: String,

	val numericID: Int,		// also its index in the 'all' list
	private val function: Function,
	private var valFunction: ValFunction	// (cached wrapper for function)

) {
	// actual C# code invoked by the intrinsic
	lateinit var code: IntrinsicCode

	// a numeric ID (used internally -- don't worry about this)
	val id: Int
		get() = numericID

	/// <summary>
	/// Add a parameter to this Intrinsic, optionally with a default value
	/// to be used if the user doesn't supply one.  You must add parameters
	/// in the same order in which arguments must be supplied.
	/// </summary>
	/// <param name="name">parameter name</param>
	/// <param name="defaultValue">default value, if any</param>
	fun AddParam(name: String, defaultValue: Value? = null) {
		function.parameters.add(Function.Param(name, defaultValue))
	}

	/// <summary>
	/// Add a parameter with a numeric default value.  (See comments on
	/// the first version o	AddParam above.)
	/// </summary>
	/// <param name="name">parameter name</param>
	/// <param name="defaultValue">default value for this parameter</param>
	fun AddParam(name: String, defaultValue: Double) {
		val defVal = when (defaultValue) {
			0.0 -> ValNumber.zero
			1.0 -> ValNumber.one
			else -> TAC.Num(defaultValue)
		}
		function.parameters.add(Function.Param(name, defVal))
	}

	/// <summary>
	/// Add a parameter with a string default value.  (See comments on
	/// the first version o	AddParam above.)
	/// </summary>
	/// <param name="name">parameter name</param>
	/// <param name="defaultValue">default value for this parameter</param>
	fun AddParam(name: String, defaultValue: String?) {
		val defVal = when {
			defaultValue.isNullOrEmpty() -> ValString.empty
			defaultValue == "__isa" -> ValString.magicIsA
			defaultValue == "self" -> _self
			else -> ValString(defaultValue)
		}
		function.parameters.add(Function.Param(name, defVal))
	}

	/// <summary>
	/// GetFunc is used internally by the compiler to get the MiniScript function
	/// that makes an intrinsic call.
	/// </summary>
	fun GetFunc(): ValFunction {
		if (function.code == null) {
			// Our little wrapper function is a single opcode: CallIntrinsicA.
			// It really exists only to provide a local variable context for the parameters.
			function.code = listOf(TAC.Line(TAC.LTemp(0), TAC.Line.Op.CallIntrinsicA, TAC.Num(numericID.toDouble())))
		}
		return valFunction
	}

	companion object {
		// static map from Values to short names, used when displaying lists/maps;
		// feel free to add to this any values (especially lists/maps) provided
		// by your own intrinsics.
		val shortNames = mutableMapOf<Value, String>()

		internal val all = mutableListOf<Intrinsic?>(null)
		internal val nameMap = mutableMapOf<String, Intrinsic>()

		private val _self = ValString("self")

		/// <summary>
		/// Factory method to create a new Intrinsic, filling out its name as given,
		/// and other internal properties as needed.  You'll still need to add any
		/// parameters, and define the code it runs.
		/// </summary>
		/// <param name="name">intrinsic name</param>
		/// <returns>freshly minted (but empty) static Intrinsic</returns>
		fun Create(name: String): Intrinsic {
			val newFunction = Function(null)
			val result = Intrinsic(
				name = name,
				numericID = all.size,
				function = newFunction,
				valFunction = ValFunction(newFunction)
			)
			all.add(result)
			nameMap[name] = result
			return result
		}

		/// <summary>
		/// Look up an Intrinsic by its internal numeric ID.
		/// </summary>
		fun GetByID(id: Int): Intrinsic? {
			return all[id]
		}

		/// <summary>
		/// Look up an Intrinsic by its name.
		/// </summary>
		fun GetByName(name: String): Intrinsic? {
			Intrinsics.InitIfNeeded()
			return nameMap.getOrElse(name, { null })
		}

		/// <summary>
		/// Internally-used function to execute an intrinsic (by ID) given a
		/// context and a partial result.
		/// </summary>
		fun Execute(id: Int, context: TAC.Context, partialResult: Result?): Result {
			val item = GetByID(id)
			return item!!.code(context, partialResult)
		}
	}

	/// <summary>
	/// Result represents the result of an intrinsic call.  An intrinsic will either
	/// be done with its work, or not yet done (e.g. because it's waiting for something).
	/// If it's done, set done=true, and store the result Value in result.
	/// If it's not done, set done=false, and store any partial result in result (and
	/// then your intrinsic will get invoked with this Result passed in as partialResult).
	/// </summary>
	class Result(
		/**
		 * final result if done; in-progress data if not done
		 */
		val result: Value?,
		/**
		 * true if our work is complete; false if we need to Continue
		 */
		val done: Boolean = true) {

		/// <summary>
		/// Result constructor for a simple numeric result.
		/// </summary>
		constructor(resultNum: Double): this(ValNumber(resultNum), true)

		/// <summary>
		/// Result constructor for a simple numeric result.
		/// </summary>
		constructor(resultNum: Int): this(ValNumber(resultNum.toDouble()), true)

		/// <summary>
		/// Result constructor for a simple string result.
		/// </summary>
		constructor(resultStr: String?) : this(
			if (resultStr.isNullOrEmpty()) ValString.empty else ValString(resultStr),
			true
		)

		companion object {
			/// <summary>
			/// Result.Null: static Result representing null (no value).
			/// </summary>
			val Null = Result(null, true)

			/// <summary>
			/// Result.EmptyString: static Result representing "" (empty string).
			/// </summary>
			val EmptyString = Result(ValString.empty)

			/// <summary>
			/// Result.True: static Result representing true (1.0).
			/// </summary>
			val True = Result(ValNumber.one, true)

			/// <summary>
			/// Result.True: static Result representing false (0.0).
			/// </summary>
			val False = Result(ValNumber.zero, true)

			/// <summary>
			/// Result.Waiting: static Result representing a need to wait,
			/// with no in-progress value.
			/// </summary>
			val Waiting = Result(null, false)
		}
	}
}


/// <summary>
/// Intrinsics: a static class containing all of the standard MiniScript
/// built-in intrinsics.  You shouldn't muck with these, but feel free
/// to browse them for lots of examples of how to write your own intrinics.
/// </summary>
object Intrinsics {
	private var initialized = false

	private data class KeyedValue(var sortKey: Value?, val value: Value?)

	private const val RND_SEED_MULTIPLIER = 1434562
	private lateinit var random: Random

	/// <summary>
	/// InitIfNeeded: called automatically during script setup to make sure
	/// that all our standard intrinsics are defined.  Note how we use a
	/// private bool flag to ensure that we don't create our intrinsics more
	/// than once, no matter how many times this method is called.
	/// </summary>
	fun InitIfNeeded() {
		if (initialized) return    // our work is already done; bail out.
		initialized = true

		// abs(x)
		Intrinsic.Create("abs").apply {
			AddParam("x", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				Intrinsic.Result(context.GetVar("x")!!.DoubleValue().absoluteValue)
			}
		}

		// acos(x)
		Intrinsic.Create("acos").apply {
			AddParam("x", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
			Intrinsic.Result(acos(context.GetVar("x")!!.DoubleValue()))
			}
		}

		// asin(x)
		Intrinsic.Create("asin").apply {
			AddParam("x", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
			Intrinsic.Result(asin(context.GetVar("x")!!.DoubleValue()))
			}
		}

		// atan(x)
		Intrinsic.Create("atan").apply {
			AddParam("x", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
			Intrinsic.Result(atan(context.GetVar("x")!!.DoubleValue()))
			}
		}

		// char(i)
		Intrinsic.Create("char").apply {
			AddParam("codePoint", 65.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val codepoint = context.GetVar("codePoint")!!.IntValue()
				// FIXME: This will convert from UTF-16 to char, NOT from UTF32!
				val s = codepoint.toChar().toString()
				Intrinsic.Result(s)
			}
		}

		// ceil(x)
		Intrinsic.Create("ceil").apply {
			AddParam("x", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
			Intrinsic.Result(ceil(context.GetVar("x")!!.DoubleValue()))
			}
		}

		// code(s)
		Intrinsic.Create("code").apply {
			AddParam("self")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				var codepoint = 0
				// FIXME: This will convert to UTF-16 from char, NOT to UTF32!
				if (self != null) codepoint = self.toString()[0].toInt()
				Intrinsic.Result(codepoint)
			}
		}

		// cos(radians)
		Intrinsic.Create("cos").apply {
			AddParam("radians", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
			Intrinsic.Result(cos(context.GetVar("radians")!!.DoubleValue()))
			}
		}

		// floor(x)
		Intrinsic.Create("floor").apply {
			AddParam("x", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
			Intrinsic.Result(floor(context.GetVar("x")!!.DoubleValue()))
			}
		}

		// funcRef
		Intrinsic.Create("funcRef").apply {
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val localVm = context.vm!!
				if (localVm.functionType == null) {
					localVm.functionType = FunctionType().EvalCopy(localVm.globalContext)
				}
				Intrinsic.Result(localVm.functionType)
			}
		}

		// hash
		Intrinsic.Create("hash").apply {
			AddParam("obj")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val value = context.GetVar("obj")
				Intrinsic.Result(value!!.Hash())
			}
		}

		// hasIndex
		Intrinsic.Create("hasIndex").apply {
			AddParam("self")
			AddParam("index")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				val index = context.GetVar("index")

				when (self) {
					is ValList -> {
						if (index !is ValNumber) {
							Intrinsic.Result.False
						} else {
							val list = self.values
							var i = index.IntValue()
							Intrinsic.Result(ValNumber.Truth(i >= -list.size && i < list.size))
						}
					}
					is ValString -> {
						val str = self.value
						val i = index!!.IntValue()
						Intrinsic.Result(ValNumber.Truth(i >= -str.length && i < str.length))
					}
					is ValMap -> {
						val map = self
						Intrinsic.Result(ValNumber.Truth(map.ContainsKey(index!!)))
					}
					else -> Intrinsic.Result.Null
				}
			}
		}

		// indexes
		//	Returns the keys of a dictionary, or the indexes for a string or list.
		Intrinsic.Create("indexes").apply {
			AddParam("self")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				when (self) {
					is ValMap -> {
						Intrinsic.Result(ValList(self.map.keys))
					}
					is ValString -> {
						val str = self.value
						val indexes = List<Value>(str.length) { TAC.Num(it) }
						Intrinsic.Result(ValList(indexes))
					}
					is ValList -> {
						val list = self.values
						val indexes = List<Value>(list.size) { TAC.Num(it) }
						Intrinsic.Result(ValList(indexes))
					}
					else -> Intrinsic.Result.Null
				}
			}
		}

		// indexOf
		//	Returns index or key of the given value, or if not found, returns null.
		Intrinsic.Create("indexOf").apply {
			AddParam("self");
			AddParam("value");
			AddParam("after");

			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self");
				val value = context.GetVar("value");
				val after = context.GetVar("after");
				when (self) {
					is ValList -> {
						val list = self.values;
						val idx: Int;
						val result: Intrinsic.Result? = if (after == null) {
							idx = list.indexOfFirst { x -> if (x == null) value == null else x.Equality(value) == 1.0 }
							null
						}
						else {
							var afterIdx = after.IntValue();
							if (afterIdx < -1) afterIdx += list.size;
							if (afterIdx < -1 || afterIdx >= list.size - 1) {
								idx = -1
								Intrinsic.Result.Null
							} else {
								idx = list.indexOfFirst(afterIdx + 1) { x -> if (x == null) value == null else x.Equality(value) == 1.0 }
								null
							}
						}
						result ?: if (idx >= 0) {
							Intrinsic.Result(idx)
						} else {
							Intrinsic.Result.Null
						}
					}
					is ValString -> {
						val str = self.value;
						val s = value.toString();
						val idx: Int;
						val result = if (after == null) {
							idx = str.indexOf(s)
							null
						} else {
							var afterIdx = after.IntValue();
							if (afterIdx < -1) afterIdx += str.length;
							if (afterIdx < -1 || afterIdx >= str.length - 1) {
								idx = -1;Intrinsic.Result.Null
							} else {
								idx = str.indexOf(s, afterIdx + 1);
								null
							}
						}
						result ?: if (idx >= 0) {
							Intrinsic.Result(idx);
						} else {
							Intrinsic.Result.Null
						}
					}
					is ValMap -> {
						val map = self;
						var sawAfter = (after == null);
						var result = Intrinsic.Result.Null
						for (entry in self.map) {
							if (!sawAfter) {
								if (entry.key.Equality(after) == 1.0) sawAfter = true;
							} else {
								if (entry.value!!.Equality(value) == 1.0) {
									result = Intrinsic.Result(entry.key)
									break
								};
							}
						}
						result
					}
					else -> Intrinsic.Result.Null
				};
			}
		}

		// insert
		Intrinsic.Create("insert").apply {
			AddParam("self")
			AddParam("index")
			AddParam("value")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				val index = context.GetVar("index")
				val value = context.GetVar("value")
				if (index == null) throw MSRuntimeException("insert: index argument required")
				if (index !is ValNumber) throw MSRuntimeException("insert: number required for index argument")

				var idx = index.IntValue()
				when (self) {
					is ValList -> {
						val list = self.values
						if (idx < 0) idx += list.size + 1    // +1 because we are inserting AND counting from the end.
						Check.Range(idx, 0, list.size)    // and allowing all the way up to .size here, because insert.
						list.add(idx, value)
						Intrinsic.Result(self)
					}
					is ValString -> {
						var s = self.toString()
						if (idx < 0) idx += s.length + 1
						Check.Range(idx, 0, s.length)
						s = s.substring(0, idx) + value.toString() + s.substring(idx)
						Intrinsic.Result(s)
					}
					else -> throw MSRuntimeException("insert called on invalid type")
				}
			}
		}

		// self.join
		Intrinsic.Create("join").apply {
			AddParam("self")
			AddParam("delimiter", " ")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				val delim = context.GetVar("delimiter").toString()
				if (self !is ValList) {
					Intrinsic.Result(self)
				} else {
					val list = List(self.values.size) { self.values[it]?.toString() }
					val result = list.joinToString(delim)
					Intrinsic.Result(result)
				}
			}
		}

		// self.len
		Intrinsic.Create("len").apply {
			AddParam("self")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				when (val self = context.GetVar("self")) {
					is ValList -> {
						val list = self.values
						Intrinsic.Result(list.size)
					}
					is ValString -> {
						val str = self.value
						Intrinsic.Result(str.length)
					}
					is ValMap -> Intrinsic.Result(self.Count)
					else -> Intrinsic.Result.Null
				}
			}
		}

		// list type
		Intrinsic.Create("list").apply {
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val localVm = context.vm!!
				if (localVm.listType == null) {
					localVm.listType = ListType().EvalCopy(localVm.globalContext)
				}
				Intrinsic.Result(localVm.listType)
			}
		}

		// log(x, base)
		Intrinsic.Create("log").apply {
			AddParam("x", 0.0)
			AddParam("base", 10.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val x = context.GetVar("x")!!.DoubleValue()
				val b = context.GetVar("base")!!.DoubleValue()
				val result = if ((b - 2.718282).absoluteValue < 0.000001) {
					log(x, E)
				} else {
					log(x, b)
				}
				Intrinsic.Result(result)
			}
		}

		// s.lower
		Intrinsic.Create("lower").apply {
			AddParam("self")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				when (self) {
					is ValString -> {
						val str = self.value
						Intrinsic.Result(str.toLowerCase())
					}
					else -> Intrinsic.Result(self)
				}
			}
		}

		// map type
		Intrinsic.Create("map").apply {
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val localVm = context.vm!!
				if (localVm.mapType == null) {
					localVm.mapType = MapType().EvalCopy(localVm.globalContext)
				}
				Intrinsic.Result(localVm.mapType)
			}
		}

		// number type
		Intrinsic.Create("number").apply {
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val localVm = context.vm!!
				if (localVm.numberType == null) {
					localVm.numberType = NumberType().EvalCopy(localVm.globalContext)
				}
				Intrinsic.Result(localVm.numberType)
			}
		}

		// pi
		Intrinsic.Create("pi").apply {
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				Intrinsic.Result(PI)
			}
		}

		// print(s)
		Intrinsic.Create("print").apply {
			AddParam("s", ValString.empty)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val s = context.GetVar("s")
				val localVm = context.vm!!
				if (s != null) localVm.standardOutput(s.toString())
				else localVm.standardOutput("null")

				Intrinsic.Result.Null
			}
		}

		// self.pop(x)
		//	removes and returns the last item in a list (or arbitrary key of a map)
		Intrinsic.Create("pop").apply {
			AddParam("self")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				when (val self = context.GetVar("self")) {
					is ValList -> {
						val list = self.values
						if (list.size < 1) {
							Intrinsic.Result.Null
						} else {
							val result = list[list.size - 1]
							list.removeAt(list.size - 1)
							Intrinsic.Result(result)
						}
					}
					is ValMap -> {
						if (self.map.size < 1) {
							Intrinsic.Result.Null
						} else {
							// FIXME: Shouldn't this return the VALUE and not the KEY?
							val result = self.map.keys.first()
							self.map.remove(result)
							Intrinsic.Result(result)
						}
					}
					else -> Intrinsic.Result.Null
				}
			}
		}

		// self.pull(x)
		//	removes and returns the first item in a list (or arbitrary key of a map)
		Intrinsic.Create("pull").apply {
			AddParam("self")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				when (val self = context.GetVar("self")) {
					is ValList -> {
						val list = self.values
						if (list.size < 1) { Intrinsic.Result.Null } else {
							val result = list[0]
							list.removeAt(0)
							Intrinsic.Result(result)
						}
					}
					is ValMap -> {
						if (self.map.size < 1) { Intrinsic.Result.Null } else {
							val result = self.map.keys.first()
							self.map.remove(result)
							Intrinsic.Result(result)
						}
					}
					else -> Intrinsic.Result.Null
				}
			}
		}

		// self.push(x)
		//	appends an item to a list (or inserts in a map); returns self
		Intrinsic.Create("push").apply {
			AddParam("self")
			AddParam("value")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				val value = context.GetVar("value")
				when (self) {
					is ValList -> {
						val list = self.values
						list.add(value)
						Intrinsic.Result(self)
					}
					is ValMap -> {
						self.map[value!!] = ValNumber.one
						Intrinsic.Result(self)
					}
					else -> Intrinsic.Result.Null
				}
			}
		}

		// range(from, to, step)
		Intrinsic.Create("range").apply {
			AddParam("from", 0.0)
			AddParam("to", 0.0)
			AddParam("step")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val p0 = context.GetVar("from")!!
				val p1 = context.GetVar("to")!!
				val p2 = context.GetVar("step")

				val fromVal = p0.DoubleValue()
				val toVal = p1.DoubleValue()
				var step = if (toVal >= fromVal) 1.0 else -1.0
				if (p2 is ValNumber) step = p2.value

				if (step == 0.0) throw MSRuntimeException("range() error (step==0)")

				val values : List<Value>
				val count = ((toVal - fromVal) / step).toInt() + 1

				if (count > ValList.maxSize) throw MSRuntimeException("list too large")

				try {
					values = List(count) { idx -> TAC.Num(fromVal + (step * idx)) }
				} catch (e: Exception) {
					// uh-oh... probably out-of-memory exception; clean up and bail out
					throw LimitExceededException("range() error", e)
				}
				Intrinsic.Result(ValList(values))
			}
		}

		// remove(self, key or index or substring)
		// 		list: mutated in place, returns null, error if index out of range
		//		map: mutated in place; returns 1 if key found, 0 otherwise
		//		string: returns new string with first occurrence of k removed
		Intrinsic.Create("remove").apply {
			AddParam("self")
			AddParam("k")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				val k = context.GetVar("k")

				if (self == null || k == null) throw MSRuntimeException("argument to 'remove' must not be null")

				when (self) {
					is ValMap -> {
						if (self.map.containsKey(k)) {
							self.map.remove(k)
							Intrinsic.Result(ValNumber.one)
						} else {
							Intrinsic.Result(ValNumber.zero)
						}
					}
					is ValList -> {
						var idx = k.IntValue()
						val values = self.values

						if (idx < 0) idx += values.size
						Check.Range(idx, 0, values.size-1)
						values.removeAt(idx)

						Intrinsic.Result.Null
					}
					is ValString -> {
						val selfStr = self
						val substr = k.toString()
						val foundPos = selfStr.value.indexOf(substr)

						if (foundPos < 0)
							Intrinsic.Result(self)
						else {
							Intrinsic.Result(selfStr.value.removeRange(foundPos, substr.length + foundPos))
						}
					}
					else -> throw TypeException("Type Error: 'remove' requires map, list, or string")
				}
			}
		}

		// replace(self, value or substring, new value/substring)
		// 		list: mutated in place, returns self
		//		map: mutated in place; returns self
		//		string: returns new string with occurrences of oldval replaced
		Intrinsic.Create("replace").apply {
			AddParam("self");
			AddParam("oldval");
			AddParam("newval");
			AddParam("maxCount");
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self") ?: throw MSRuntimeException("argument to 'replace' must not be null");
				val oldval = context.GetVar("oldval");
				val newval = context.GetVar("newval");
				val maxCountVal = context.GetVar("maxCount");

				var maxCount = -1;
				var result: Intrinsic.Result? = null
				if (maxCountVal != null) {
					maxCount = maxCountVal.IntValue();
					if (maxCount < 1) result = Intrinsic.Result(self);
				}
				var count = 0;
				if (result != null) {
					result!!
				} else {
					when (self) {
						is ValMap -> {
							val selfMap = self;
							// C# doesn't allow changing even the values while iterating
							// over the keys.  So gather the keys to change, then change
							// them afterwards.
							var keysToChange: MutableList<Value>? = null;
							for (entry in selfMap.map) {
								if (entry.value!!.Equality(oldval) == 1.0) {
									if (keysToChange == null) {
										keysToChange = mutableListOf<Value>()
									}
									keysToChange.add(entry.key);
									count++;
									if (maxCount > 0 && count == maxCount) break;
								}
							}
							if (keysToChange != null) {
								for (k in keysToChange) {
									selfMap.map[k] = newval;
								}
							}
							Intrinsic.Result(self);
						}
						is ValList -> {
							val selfList = self;
							var idx = -1;
							while (true) {
								idx = selfList.values.indexOfFirst(idx + 1) { x -> x!!.Equality(oldval) == 1.0 };
								if (idx < 0) break;
								selfList.values[idx] = newval;
								count++;
								if (maxCount > 0 && count == maxCount) break;
							}
							Intrinsic.Result(self);
						}
						is ValString -> {
							var str = self.toString();
							val oldstr = oldval.toString();
							val newstr = newval.toString();
							var idx = 0;
							while (true) {
								idx = str.indexOf(oldstr, idx);
								if (idx < 0) break;
								str = str.substring(0, idx) + newstr + str.substring(idx + oldstr.length);
								idx += newstr.length;
								count++;
								if (maxCount > 0 && count == maxCount) break;
							}
							Intrinsic.Result(str);
						}
						else -> throw TypeException("Type Error: 'replace' requires map, list, or string");
					}
				}
			}
		}

		fun round(num: Double, decimalPlaces: Int): Double {
			var multiplier = 10.0.pow(decimalPlaces)
			return round(num * multiplier) / multiplier
		}

		// round(x, decimalPlaces)
		Intrinsic.Create("round").apply {
			AddParam("x", 0.0)
			AddParam("decimalPlaces", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val num = context.GetVar("x")!!.DoubleValue()
				val decimalPlaces = context.GetVar("decimalPlaces")!!.IntValue()
				Intrinsic.Result(round(num, decimalPlaces))
			}
		}

		// rnd(seed)
		Intrinsic.Create("rnd").apply {
			AddParam("seed")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				// TODO: We might want to replace `Random` with some specific implementation (like xoroshiro)
				if (!::random.isInitialized) random = Random(context.vm!!.runTime.toInt() * RND_SEED_MULTIPLIER)
				val seed = context.GetVar("seed")
				if (seed != null) random = Random(seed.IntValue())
				Intrinsic.Result(random.nextDouble())
			}
		}

		// sign(x)
		Intrinsic.Create("sign").apply {
			AddParam("x", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				Intrinsic.Result(sign(context.GetVar("x")!!.DoubleValue()))
			}
		}

		// sin(radians)
		Intrinsic.Create("sin").apply {
			AddParam("radians", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				Intrinsic.Result(sin(context.GetVar("radians")!!.DoubleValue()))
			}
		}

		// slice(seq, from, to)
		Intrinsic.Create("slice").apply {
			AddParam("seq")
			AddParam("from", 0.0)
			AddParam("to")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val seq = context.GetVar("seq")
				var fromIdx = context.GetVar("from")!!.IntValue()
				val toVal = context.GetVar("to")
				var toIdx = 0
				if (toVal != null) toIdx = toVal.IntValue()

				when (seq) {
					is ValList -> {
						val list = seq.values
						if (fromIdx < 0) fromIdx += list.size
						if (fromIdx < 0) fromIdx = 0
						if (toVal == null) toIdx = list.size
						if (toIdx < 0) toIdx += list.size
						if (toIdx > list.size) toIdx = list.size
						val newValues = if (fromIdx < list.size && toIdx > fromIdx) {
							List(toIdx - fromIdx) { idx ->
								list[idx + fromIdx]
							}
						} else {
							emptyList()
						}
						val slice = ValList(newValues)
						Intrinsic.Result(slice)
					}
					is ValString -> {
						val str = seq.value
						if (fromIdx < 0) fromIdx += str.length
						if (fromIdx < 0) fromIdx = 0
						if (toVal == null) toIdx = str.length
						if (toIdx < 0) toIdx += str.length
						if (toIdx > str.length) toIdx = str.length

						when {
							toIdx - fromIdx <= 0 -> Intrinsic.Result.EmptyString
							else -> Intrinsic.Result(str.substring(fromIdx, toIdx))
						}
					}
					else -> Intrinsic.Result.Null
				}
			}
		}

		// list.sort(byKey=null)
		Intrinsic.Create("sort").apply {
			AddParam("self")
			AddParam("byKey")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				val list = self as? ValList
				if (list == null || list.values.size < 2) {
					Intrinsic.Result(self)
				} else {
					val byKey = context.GetVar("byKey")
					if (byKey == null) {
						// Simple case: sort the values as themselves
						list.values.sortBy { it }
					} else {
						// Harder case: sort by a key.
						val count = list.values.size
						val arr = Array(count) { KeyedValue(null, list.values[it]) }
						// The key for each item will be the item itself, unless it is a map, in which
						// case it's the item indexed by the given key.  (Works too for lists if our
						// index is an integer.)
						val byKeyInt = byKey.IntValue()
						for (i in 0 until count) {
							when (val item = list.values[i]) {
								is ValMap -> arr[i].sortKey = item.Lookup(byKey)
								is ValList -> {
									arr[i].sortKey =
										if (byKeyInt > -item.values.size && byKeyInt < item.values.size) {
											item.values[byKeyInt]
										} else {
											null
										}
								}
							}
						}
						// Now sort our list of keyed values, by key
						arr.sortBy { it.sortKey }
						// And finally, convert that back into our list
						var idx = 0
						for (kv in arr) {
							list.values[idx++] = kv.value
						}
					}
					Intrinsic.Result(list)
				}
			}
		}

		// split(self, delimiter, maxCount)
		Intrinsic.Create("split").apply {
			AddParam("self")
			AddParam("delimiter", " ")
			AddParam("maxCount", -1.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")!!.toString()
				val delim = context.GetVar("delimiter")!!.toString()
				val maxCount = context.GetVar("maxCount")!!.IntValue()
				val result = ValList()
				var pos = 0
				while (pos < self.length) {
					var nextPos: Int
					nextPos = when {
						maxCount >= 0 && result.values.size == maxCount - 1 -> self.length
						delim.isEmpty() -> pos+1
						else -> self.indexOf(delim, pos)
					}
					if (nextPos < 0) nextPos = self.length
					result.values.add(ValString(self.substring(pos, nextPos)))
					pos = nextPos + delim.length
					if (pos == self.length && delim.isNotEmpty()) {
						result.values.add(ValString.empty)
					}
				}
				Intrinsic.Result(result)
			}
		}

		// sqrt(x)
		Intrinsic.Create("sqrt").apply {
			AddParam("x", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				Intrinsic.Result(sqrt(context.GetVar("x")!!.DoubleValue()))
			}
		}

		// str(x)
		Intrinsic.Create("str").apply {
			AddParam("x", ValString.empty)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				Intrinsic.Result(context.GetVar("x")!!.toString())
			}
		}

		// string type
		Intrinsic.Create("string").apply {
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val localVm = context.vm!!
				if (localVm.stringType == null) {
					localVm.stringType = StringType().EvalCopy(localVm.globalContext)
				}
				Intrinsic.Result(localVm.stringType)
			}
		}

		// shuffle(self)
		Intrinsic.Create("shuffle").apply {
			AddParam("self")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				if (!::random.isInitialized) random = Random(context.vm!!.runTime.toInt() * RND_SEED_MULTIPLIER)
				if (self is ValList) {
					val list = self.values
					list.shuffle(random)
				} else if (self is ValMap) {
					val map = self.map
					// Fisher-Yates again, but this time, what we're swapping
					// is the values associated with the keys, not the keys themselves.
					val keys = map.keys.toTypedArray()
					for (i in keys.lastIndex downTo 1) {
						val j = random.nextInt(i+1)
						val keyi = keys[i]
						val keyj = keys[j]
						val temp = map[keyj]
						map[keyj] = map[keyi]
						map[keyi] = temp
					}
				}
				Intrinsic.Result.Null
			}
		}

		// sum(self)
		Intrinsic.Create("sum").apply {
			AddParam("self")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				val sum = when (self) {
					is ValList -> self.values.sumByDouble { it!!.DoubleValue() }
					is ValMap -> self.map.values.sumByDouble { it!!.DoubleValue() }
					else -> 0.0
				}
				Intrinsic.Result(sum)
			}
		}

		// tan(radians)
		Intrinsic.Create("tan").apply {
			AddParam("radians", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				Intrinsic.Result(tan(context.GetVar("radians")!!.DoubleValue()))
			}
		}

		// time
		Intrinsic.Create("time").apply {
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				Intrinsic.Result(context.vm!!.runTime)
			}
		}

		// s.upper
		Intrinsic.Create("upper").apply {
			AddParam("self")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				when (val self = context.GetVar("self")) {
					is ValString -> {
						val str = self.value
						Intrinsic.Result(str.toUpperCase())
					}
					else -> Intrinsic.Result(self)
				}
			}
		}

		// val(s)
		Intrinsic.Create("val").apply {
			AddParam("self", 0.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				when (self) {
					is ValNumber -> Intrinsic.Result(self)
					is ValString -> {
						val result = self.toString().toDoubleOrNull() ?: 0.0
						Intrinsic.Result(result)
					}
					else -> Intrinsic.Result.Null
				}
			}
		}

		// values
		//  Returns the values of a dictionary, or the characters of a string.
		//  (Returns any other value as-is.)
		Intrinsic.Create("values").apply {
			AddParam("self")
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val self = context.GetVar("self")
				when (self) {
					is ValMap -> {
						Intrinsic.Result(ValList(self.map.values))
					}
					is ValString -> {
						val str = self.value
						val values = List<Value>(str.length) { TAC.Str(str[it]) }
						
						Intrinsic.Result(ValList(values))
					}
					else -> Intrinsic.Result(self)
				}
			}
		}

		// version
		/*Intrinsic.Create("version").apply {
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				if (context.vm.versionMap == null) {
					val d = ValMap();
					d["miniscript"] = ValNumber(1.4);

					// Getting the build date is annoyingly hard in C#.
					// This will work if the assembly.cs file uses the version format: 1.0.*
					DateTime buildDate;
					System.Version version = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version;
					buildDate = new DateTime(2000, 1, 1);
					buildDate = buildDate.AddDays(version.Build);
					buildDate = buildDate.AddSeconds(version.Revision * 2);
					d["buildDate"] = ValString(buildDate.toString("yyyy-MM-dd"));

					d["host"] = ValNumber(HostInfo.version);
					d["hostName"] = ValString(HostInfo.name);
					d["hostInfo"] = ValString(HostInfo.info);

					context.vm.versionMap = d;
				}
				Intrinsic.Result(context.vm.versionMap);
			}
		}*/

		// wait(seconds)
		Intrinsic.Create("wait").apply {
			AddParam("seconds", 1.0)
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				val now = context.vm!!.runTime
				if (partialResult == null) {
					// Just starting our wait; calculate end time and return as partial result
					val interval = context.GetVar("seconds")!!.DoubleValue()
					Intrinsic.Result(ValNumber(now + interval), false)
				} else {
					// Continue until current time exceeds the time in the partial result
					if (now > partialResult.result!!.DoubleValue()) {
						Intrinsic.Result.Null
					} else {
						partialResult
					}
				}
			}
		}

		// yield
		Intrinsic.Create("yield").apply {
			code = { context: TAC.Context, partialResult: Intrinsic.Result? ->
				context.vm!!.yielding = true
				Intrinsic.Result.Null
			}
		}
	}

	// Helper method to compile a call to Slice (when invoked directly via slice syntax).
	fun CompileSlice(code: MutableList<TAC.Line>, list: Value?, fromIdx: Value?, toIdx: Value?, resultTempNum: Int) {
		code.add(TAC.Line(null, TAC.Line.Op.PushParam, list))
		code.add(TAC.Line(null, TAC.Line.Op.PushParam, fromIdx ?: TAC.Num(0.0)))
		code.add(TAC.Line(null, TAC.Line.Op.PushParam, toIdx))// toIdx == null ? TAC.Num(0) : toIdx));
		val func = Intrinsic.GetByName("slice")!!.GetFunc()
		code.add(TAC.Line(TAC.LTemp(resultTempNum), TAC.Line.Op.CallFunctionA, func, TAC.Num(3.0)))
	}

	private var _functionType: ValMap? = null

	/// <summary>
	/// FunctionType: a static map that represents the Function type.
	/// </summary>
	fun FunctionType(): ValMap {
		if (_functionType == null) {
			_functionType = ValMap()
		}
		return _functionType!!
	}

	private var _listType: ValMap? = null

	/// <summary>
	/// ListType: a static map that represents the List type, and provides
	/// intrinsic methods that can be invoked on it via dot syntax.
	/// </summary>
	fun ListType(): ValMap {
		if (_listType == null) {
			val newListType = ValMap()

			newListType["hasIndex"] = Intrinsic.GetByName("hasIndex")!!.GetFunc()
			newListType["indexes"] = Intrinsic.GetByName("indexes")!!.GetFunc()
			newListType["indexOf"] = Intrinsic.GetByName("indexOf")!!.GetFunc()
			newListType["insert"] = Intrinsic.GetByName("insert")!!.GetFunc()
			newListType["join"] = Intrinsic.GetByName("join")!!.GetFunc()
			newListType["len"] = Intrinsic.GetByName("len")!!.GetFunc()
			newListType["pop"] = Intrinsic.GetByName("pop")!!.GetFunc()
			newListType["pull"] = Intrinsic.GetByName("pull")!!.GetFunc()
			newListType["push"] = Intrinsic.GetByName("push")!!.GetFunc()
			newListType["shuffle"] = Intrinsic.GetByName("shuffle")!!.GetFunc()
			newListType["sort"] = Intrinsic.GetByName("sort")!!.GetFunc()
			newListType["sum"] = Intrinsic.GetByName("sum")!!.GetFunc()
			newListType["remove"] = Intrinsic.GetByName("remove")!!.GetFunc()
			newListType["replace"] = Intrinsic.GetByName("replace")!!.GetFunc()
			newListType["values"] = Intrinsic.GetByName("values")!!.GetFunc()

			_listType = newListType
		}
		return _listType!!
	}
	
	private var _stringType : ValMap? = null

	/// <summary>
	/// StringType: a static map that represents the String type, and provides
	/// intrinsic methods that can be invoked on it via dot syntax.
	/// </summary>
	public fun StringType() : ValMap {
		if (_stringType == null) {
			val newStringType = ValMap()

			newStringType["hasIndex"] = Intrinsic.GetByName("hasIndex")!!.GetFunc()
			newStringType["indexes"] = Intrinsic.GetByName("indexes")!!.GetFunc()
			newStringType["indexOf"] = Intrinsic.GetByName("indexOf")!!.GetFunc()
			newStringType["insert"] = Intrinsic.GetByName("insert")!!.GetFunc()
			newStringType["code"] = Intrinsic.GetByName("code")!!.GetFunc()
			newStringType["len"] = Intrinsic.GetByName("len")!!.GetFunc()
			newStringType["lower"] = Intrinsic.GetByName("lower")!!.GetFunc()
			newStringType["val"] = Intrinsic.GetByName("val")!!.GetFunc()
			newStringType["remove"] = Intrinsic.GetByName("remove")!!.GetFunc()
			newStringType["replace"] = Intrinsic.GetByName("replace")!!.GetFunc()
			newStringType["split"] = Intrinsic.GetByName("split")!!.GetFunc()
			newStringType["upper"] = Intrinsic.GetByName("upper")!!.GetFunc()
			newStringType["values"] = Intrinsic.GetByName("values")!!.GetFunc()

			_stringType = newStringType
		}
		return _stringType!!
	}

	private var _mapType: ValMap? = null

	/// <summary>
	/// MapType: a static map that represents the Map type, and provides
	/// intrinsic methods that can be invoked on it via dot syntax.
	/// </summary>
	fun MapType(): ValMap {
		if (_mapType == null) {
			val newMapType = ValMap()

			newMapType["hasIndex"] = Intrinsic.GetByName("hasIndex")!!.GetFunc()
			newMapType["indexes"] = Intrinsic.GetByName("indexes")!!.GetFunc()
			newMapType["indexOf"] = Intrinsic.GetByName("indexOf")!!.GetFunc()
			newMapType["len"] = Intrinsic.GetByName("len")!!.GetFunc()
			newMapType["pop"] = Intrinsic.GetByName("pop")!!.GetFunc()
			newMapType["push"] = Intrinsic.GetByName("push")!!.GetFunc()
			newMapType["shuffle"] = Intrinsic.GetByName("shuffle")!!.GetFunc()
			newMapType["sum"] = Intrinsic.GetByName("sum")!!.GetFunc()
			newMapType["remove"] = Intrinsic.GetByName("remove")!!.GetFunc()
			newMapType["replace"] = Intrinsic.GetByName("replace")!!.GetFunc()
			newMapType["values"] = Intrinsic.GetByName("values")!!.GetFunc()

			_mapType = newMapType
		}

		return _mapType!!
	}

	private var _numberType: ValMap? = null

	/// <summary>
	/// NumberType: a static map that represents the Number type.
	/// </summary>
	fun NumberType(): ValMap {
		if (_numberType == null) {
			_numberType = ValMap()
		}
		return _numberType!!
	}
}

private fun <E> List<E>.indexOfFirst(startIndex: Int, predicate: (E) -> Boolean): Int {
	var index = startIndex
	for (j in index until size) {
		val item = this[j]
		if (predicate(item))
			return index
		index++
	}
	return -1
}
