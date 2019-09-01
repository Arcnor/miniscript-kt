package com.arcnor.miniscript

import kotlin.math.roundToInt


/**
 * Abstract base class for the MiniScript type hierarchy.
 * Defines a number of handy methods that you can call on ANY
 * value (though some of these do nothing for some types).
 */
sealed class Value : Comparable<Value> {
	open fun Val(context: TAC.Context): Value? = this

	override fun toString() = toString(null)

	abstract fun toString(vm: TAC.Machine?): String

	/// <summary>
	/// This version of Val is like the one above, but also returns
	/// (via the output parameter) the ValMap the value was found in,
	/// which could be several steps up the __isa chain.
	/// </summary>
	/// <returns>The value.</returns>
	/// <param name="context">Context.</param>
	/// <param name="valueFoundIn">Value found in.</param>

	// , out ValMap valueFoundIn
	open fun ValWithLocation(context: TAC.Context): Pair<Value?, ValMap?> {
		return this to null
	}

	/// <summary>
	/// Similar to Val, but recurses into the sub-values contained by this
	/// value (if it happens to be a container, such as a list or map).
	/// </summary>
	/// <param name="context">context in which to evaluate</param>
	/// <returns>fully-evaluated value</returns>
	open fun FullEval(context: TAC.Context): Value {
		return this
	}

	/// <summary>
	/// Get the numeric value of this Value as an integer.
	/// </summary>
	/// <returns>this value, as signed integer</returns>
	open fun IntValue(): Int {
		return DoubleValue().toInt()
	}

	/// <summary>
	/// Get the numeric value of this Value as an unsigned integer.
	/// </summary>
	/// <returns>this value, as unsigned int</returns>
	open fun UIntValue(): UInt {
		return DoubleValue().toUInt()
	}

	/// <summary>
	/// Get the numeric value of this Value as a single-precision float.
	/// </summary>
	/// <returns>this value, as a float</returns>
	open fun FloatValue(): Float {
		return DoubleValue().toFloat()
	}

	/// <summary>
	/// Get the numeric value of this Value as a double-precision floating-point number.
	/// </summary>
	/// <returns>this value, as a double</returns>
	open fun DoubleValue(): Double {
		return 0.0                // most types don't have a numeric value
	}

	/// <summary>
	/// Get the boolean (truth) value of this Value.  By default, we consider
	/// any numeric value other than zero to be true.  (But subclasses override
	/// this with different criteria for strings, lists, and maps.)
	/// </summary>
	/// <returns>this value, as a bool</returns>
	open fun BoolValue(): Boolean {
		return IntValue() != 0
	}

	/// <summary>
	/// Get this value in the form of a MiniScript literal.
	/// </summary>
	/// <param name="recursionLimit">how deeply we can recurse, or -1 for no limit</param>
	/// <returns></returns>
	open fun CodeForm(vm: TAC.Machine?, recursionLimit:Int=-1): String {
		return toString(vm)
	}

	/// <summary>
	/// Get a hash value for this Value.  Two values that are considered
	/// equal will return the same hash value.
	/// </summary>
	/// <returns>hash value</returns>
	abstract fun Hash(recursionDepth:Int=16): Int

	/// <summary>
	/// Check whether this Value is equal to another Value.
	/// </summary>
	/// <param name="rhs">other value to compare to</param>
	/// <returns>1 if these values are considered equal; 0 if not equal; 0.5 if unsure</returns>
	abstract fun Equality(rhs: Value?, recursionDepth:Int=16): Double

	/// <summary>
	/// Can we set elements within this value?  (I.e., is it a list or map?)
	/// </summary>
	/// <returns>true if SetElem can work; false if it does nothing</returns>
	open fun CanSetElem():Boolean { return false; }

	/// <summary>
	/// Set an element associated with the given index within this Value.
	/// </summary>
	/// <param name="index">index/key for the value to set</param>
	/// <param name="value">value to set</param>
	open fun SetElem(index:Value, value:Value?) {}

	/// <summary>
	/// Return whether this value is the given type (or some subclass thereof)
	/// in the context of the given virtual machine.
	/// </summary>
	open fun IsA(type: Value?, vm: TAC.Machine): Boolean {
		return false
	}

	override fun compareTo(other: Value) = Compare(this, other)

	companion object {
		fun Compare(x:Value, y:Value):Int {
			// If either argument is a string, do a string comparison
			if (x is ValString || y is ValString) {
				val sx = x.toString()
				val sy = y.toString()
				return sx.compareTo(sy)
			}

			// If both arguments are numbers, compare numerically
			if (x is ValNumber && y is ValNumber) {
				val fx = x.value
				val fy = y.value
				if (fx < fy) return -1
				if (fx > fy) return 1
				return 0
			}
			// Otherwise, consider all values equal, for sorting purposes.
			return 0
		}
	}
}

class ValNumber(var value: Double) : Value() {
	override fun toString(vm: TAC.Machine?): String {
		// Convert to a string in the standard MiniScript way.
		return when {
			value % 1.0 == 0.0 -> // integer values as integers
				value.toInt().toString()
			value < 1E-6 && value > -1E-6 -> // very large/small numbers in exponential form
				"%.6E".format(value)
//			value > 1E10 || value < -1E10 || value < 1E-6 && value > -1E-6 -> // very large/small numbers in exponential form
//				value.toString("E6")
//			else -> // all others in decimal form, with 1-6 digits past the decimal point
//				value.toString("0.0#####")
			// FIXME: More formats
			else -> value.toString()
		}
	}

	override fun IntValue() = value.toInt()

	override fun DoubleValue()  = value

	// Any nonzero value is considered true, when treated as a bool.
	override fun BoolValue()  = value != 0.0

	override fun IsA(type: Value?, vm: TAC.Machine) = type == vm.numberType

	override fun Hash(recursionDepth: Int): Int {
		return value.hashCode()
	}

	override fun Equality(rhs: Value?, recursionDepth: Int): Double {
		return if (rhs is ValNumber && rhs.value == value) 1.0 else 0.0
	}

	companion object {
		val _zero = ValNumber(0.0)
		val _one = ValNumber(1.0)

		/// <summary>
		/// Handy accessor to a shared "zero" (0) value.
		/// IMPORTANT: do not alter the value of the object returned!
		/// </summary>
		val zero: ValNumber
			get() = _zero

		/// <summary>
		/// Handy accessor to a shared "one" (1) value.
		/// IMPORTANT: do not alter the value of the object returned!
		/// </summary>
		val one
			get() = _one

		/// <summary>
		/// Convenience method to get a reference to zero or one, according
		/// to the given boolean.  (Note that this only covers Boolean
		/// truth values; MiniScript also allows fuzzy truth values, like
		/// 0.483, but obviously this method won't help with that.)
		/// IMPORTANT: do not alter the value of the object returned!
		/// </summary>
		/// <param name="truthValue">whether to return 1 (true) or 0 (false)</param>
		/// <returns>ValNumber.one or ValNumber.zero</returns>
		fun Truth(truthValue: Boolean) = if (truthValue) one else zero

		/// <summary>
		/// Basically this just makes a ValNumber out of a double,
		/// BUT it is optimized for the case where the given value
		///	is either 0 or 1 (as is usually the case with truth tests).
		/// </summary>
		fun Truth(truthValue: Double) = when (truthValue) {
			0.0 -> zero
			1.0 -> one
			else -> ValNumber(truthValue)
		}
	}
}

open class ValString(value: String?) : Value() {
	var value: String = value ?: ""

	override fun toString(vm: TAC.Machine?) = value

	override fun CodeForm(vm: TAC.Machine?, recursionLimit: Int) = "\"" + value.replace("\"", "\"\"") + "\""

	/**
	 * Any nonempty string is considered true.
	 */
	override fun BoolValue() = value.isNotEmpty()

	override fun IsA(type: Value?, vm:TAC.Machine) = type == vm.stringType

	override fun Hash(recursionDepth: Int)  = value.hashCode()

	override fun Equality(rhs: Value?, recursionDepth: Int): Double {
		// String equality is treated the same as in C#.
		return if (rhs is ValString && rhs.value == value) 1.0 else 0.0
	}

	fun GetElem(index:Value): Value {
		var i = index.IntValue()
		if (i < 0) i += value.length
		if (i < 0 || i >= value.length) {
			throw IndexException("Index Error (string index $index out of range)")
		}
		return ValString(value.substring(i, i + 1))
	}

	companion object {
		const val maxSize = 0xFFFFFFL        // about 16M elements

		// Magic identifier for the is-a entry in the class system:
		val magicIsA = ValString("__isa")
		val _empty = ValString("")

		/// <summary>
		/// Handy accessor for an empty ValString.
		/// IMPORTANT: do not alter the value of the object returned!
		/// </summary>
		val empty: ValString
			get() = _empty
	}
}

class TempValString(s: String) : ValString(s) {
	private var next: TempValString? = null

	companion object {
		private var _tempPoolHead: TempValString? = null
		private var lockObj: Any = Any()

		fun Get(s: String): TempValString {
			synchronized(lockObj) {
				if (_tempPoolHead == null) {
					return TempValString(s)
				} else {
					val result = _tempPoolHead
					_tempPoolHead = _tempPoolHead!!.next
					result!!.value = s
					return result
				}
			}
		}

		fun Release(temp: TempValString) {
			synchronized(lockObj) {
				temp.next = _tempPoolHead
				_tempPoolHead = temp
			}
		}
	}
}

class ValList(values: Iterable<Value?>? = null) : Value() {
	// FIXME: Remove this `toMutableList()` and make this list immutable, change the code to allow this
	val values = values?.toMutableList() ?: mutableListOf()

	override fun FullEval(context : TAC.Context):Value {
		// Evaluate each of our list elements, and if any of those is
		// a variable or temp, then resolve those now.
		// CAUTION: do not mutate our original list!  We may need
		// it in its original form on future iterations.
		var result:ValList? = null
		for (i in 0 until values.size) {
			var copied = false
			val currentValue = values[i]

			if (currentValue is ValTemp || currentValue is ValVar) {
				val newVal = currentValue.Val(context)
				if (newVal != currentValue) {
					// OK, something changed, so we're going to need a new copy of the list.
					if (result == null) {
						result = ValList()
						for (j in 0 until i) {
							result.values.add(values[j])
						}
					}
					result.values.add(newVal)
					copied = true
				}
			}
			if (!copied && result != null) {
				// No change; but we have new results to return, so copy it as-is
				result.values.add(currentValue)
			}
		}
		return result ?: this
	}

	fun EvalCopy(context: TAC.Context): ValList {
		// Create a copy of this list, evaluating its members as we go.
		// This is used when a list literal appears in the source, to
		// ensure that each time that code executes, we get a new, distinct
		// mutable object, rather than the same object multiple times.
		val result = ValList()
		for (currentValue in values) {
			result.values.add(currentValue?.Val(context))
		}
		return result
	}

	override fun CodeForm(vm: TAC.Machine?, recursionLimit: Int): String {
		if (recursionLimit == 0) return "[...]"
		if (recursionLimit in 1..2 && vm != null) {
			val shortName = vm.FindShortName(this)
			if (shortName != null) return shortName
		}
		val strs = Array(values.size) { idx ->
			val value = values[idx]
			value?.CodeForm(vm, recursionLimit - 1) ?: "null"
		}
		return "[${strs.joinToString(", ")}]"
	}

	override fun toString(vm: TAC.Machine?) = CodeForm(vm, 3)

	override fun BoolValue():Boolean {
		// A list is considered true if it is nonempty.
		return values.isNotEmpty()
	}

	override fun IsA(type: Value?, vm:TAC.Machine):Boolean {
		return type == vm.listType
	}

	override fun Hash(recursionDepth:Int):Int {
		//return values.GetHashCode();
		var result = values.size.hashCode()
		if (recursionDepth < 1) return result
		for (i in 0 until values.size) {
			result = result xor values[i]!!.Hash(recursionDepth-1)
		}
		return result
	}

	override fun Equality(rhs: Value?, recursionDepth:Int): Double {
		if (rhs !is ValList) return 0.0
		val rhl = rhs.values
		if (rhl == values) return 1.0  // (same list)
		val count = values.size
		if (count != rhl.size) return 0.0
		if (recursionDepth < 1) return 0.5        // in too deep
		var result = 1.0
		for (i in 0 until count) {
			result *= values[i]!!.Equality(rhl[i], recursionDepth-1)
			if (result <= 0) break
		}
		return result
	}

	override fun CanSetElem() = true

	override fun SetElem(index:Value, value:Value?) {
		var i = index.IntValue()
		if (i < 0) i += values.size
		if (i < 0 || i >= values.size) {
			throw IndexException("Index Error (list index $index out of range)")
		}
		values[i] = value
	}

	fun GetElem(index: Value): Value? {
		var i = index.IntValue()
		if (i < 0) i += values.size
		if (i < 0 || i >= values.size) {
			throw IndexException("Index Error (list index $index out of range)")

		}
		return values[i]
	}

	companion object {
		const val maxSize = 0xFFFFFFL        // about 16M elements
	}
}

typealias AssignOverrideFunc = (key: Value, value: Value?) -> Boolean

class ValMap() : Value() {
	val map = mutableSortedMapOf<Value, Value?>(RValueEqualityComparer.instance)

	var assignOverride: AssignOverrideFunc? = null


	// A map is considered true if it is nonempty.
	override fun BoolValue() = map.isNotEmpty()


	/// <summary>
	/// Convenience method to check whether the map contains a given string key.
	/// </summary>
	/// <param name="identifier">string key to check for</param>
	/// <returns>true if the map contains that key; false otherwise</returns>
	fun ContainsKey(identifier: String): Boolean {
		val idVal = TempValString.Get(identifier)
		val result = map.containsKey(idVal)
		TempValString.Release(idVal)
		return result
	}

	/// <summary>
	/// Convenience method to check whether this map contains a given key
	/// (of arbitrary type).
	/// </summary>
	/// <param name="key">key to check for</param>
	/// <returns>true if the map contains that key; false otherwise</returns>
	fun ContainsKey(key: Value) = map.containsKey(key)

	/// <summary>
	/// Get the number of entries in this map.
	/// </summary>
	val Count: Int
		get() = map.size

	/// <summary>
	/// Return the KeyCollection for this map.
	/// </summary>
	val Keys: MutableSet<Value>
		get() = map.keys

	/// <summary>
	/// Accessor to get/set on element of this map by a string key, walking
	/// the __isa chain as needed.  (Note that if you want to avoid that, then
	/// simply look up your value in .map directly.)
	/// </summary>
	/// <param name="identifier">string key to get/set</param>
	/// <returns>value associated with that key</returns>
	operator fun get(identifier: String): Value? {
		val idVal = TempValString.Get(identifier)
		val result = Lookup(idVal)
		TempValString.Release(idVal)
		return result
	}

	operator fun set(identifier: String, value: Value?) {
		map[ValString(identifier)] = value
	}

	/// <summary>
	/// Look up the given identifier as quickly as possible, without
	/// walking the __isa chain or doing anything fancy.  (This is used
	/// when looking up local variables.)
	/// </summary>
	/// <param name="identifier">identifier to look up</param>
	/// <returns>true if found, false if not</returns>
//	fun TryGetValue(identifier:String, out Value value): Boolean {
//		var idVal = TempValString.Get(identifier);
//		bool result = map.TryGetValue(idVal, out value);
//		TempValString.Release(idVal);
//		return result;
//	}

	/// <summary>
	/// Look up a value in this dictionary, walking the __isa chain to find
	/// it in a parent object if necessary.
	/// </summary>
	/// <param name="key">key to search for</param>
	/// <returns>value associated with that key, or null if not found</returns>
	fun Lookup(key: Value): Value? {
		var obj: ValMap? = this
		while (obj != null) {
			if (obj.map.containsKey(key)) return obj.map[key]
			if (!obj.map.containsKey(ValString.magicIsA)) break
			obj = obj.map[ValString.magicIsA] as ValMap
		}
		return null
	}

	/// <summary>
	/// Look up a value in this dictionary, walking the __isa chain to find
	/// it in a parent object if necessary; return both the value found an
	/// (via the output parameter) the map it was found in.
	/// </summary>
	/// <param name="key">key to search for</param>
	/// <returns>value associated with that key, or null if not found</returns>
	fun LookupWithLocation(key: Value): Pair<Value?, ValMap?> {
		var obj: ValMap? = this

		while (obj != null) {
			if (obj.map.containsKey(key)) {
				return obj.map[key] to obj
			}

			if (!obj.map.containsKey(ValString.magicIsA)) break
			obj = obj.map[ValString.magicIsA] as ValMap
		}

		return null to null
	}


	override fun FullEval(context: TAC.Context): Value {
		// Evaluate each of our elements, and if any of those is
		// a variable or temp, then resolve those now.
		for (entry in map) {	// TODO: something more efficient here.
			val key = entry.key
			val value = entry.value

			if (key is ValTemp || key is ValVar) {
				map.remove(key)
				map[key.Val(context)!!] = value
			}
			if (value is ValTemp || value is ValVar) {
				map[key] = value.Val(context)
			}
		}
		return this
	}


	fun EvalCopy(context: TAC.Context): ValMap {
		// Create a copy of this map, evaluating its members as we go.
		// This is used when a map literal appears in the source, to
		// ensure that each time that code executes, we get a new, distinct
		// mutable object, rather than the same object multiple times.
		val result = ValMap()
		for (entry in map) {
			var key = entry.key
			var value = entry.value

			if (key is ValTemp || key is ValVar) key = key.Val(context)!!
			if (value is ValTemp || value is ValVar) value = value.Val(context)
			result.map[key] = value
		}
		return result
	}

	override fun CodeForm(vm: TAC.Machine?, recursionLimit: Int): String {
		if (recursionLimit == 0) return "{...}"
		if (recursionLimit in 1..2 && vm != null) {
			val shortName = vm.FindShortName(this)
			if (shortName != null) return shortName
		}
		val entries = map.entries.toTypedArray()

		val strs = Array(map.size) { idx ->
			val entry = entries[idx]
			val key = entry.key
			val value = entry.value

			var nextRecurLimit = recursionLimit - 1
			if (key == ValString.magicIsA) nextRecurLimit = 1

			val k = key.CodeForm(vm, nextRecurLimit)
			val v = value?.CodeForm(vm, nextRecurLimit) ?: "null"

			"$k: $v"
		}

		return strs.joinToString(", ", prefix = "{", postfix = "}")
	}

	override fun toString(vm: TAC.Machine?) = CodeForm(vm, 3)

	override fun IsA(type: Value?, vm: TAC.Machine): Boolean {
		// If the given type is the magic 'map' type, then we're definitely
		// one of those.  Otherwise, we have to walk the __isa chain.
		if (type == vm.mapType) return true
		var p = map.getOrElse(ValString.magicIsA, { null })
		while (p != null) {
			if (p == type) return true
			if (p !is ValMap) return false
			p = p.map.getOrElse(ValString.magicIsA, { null })
		}
		return false
	}

	override fun Hash(recursionDepth:Int) :Int {
		//return map.GetHashCode();
		var result = map.size.hashCode()

		if (recursionDepth < 0) return result  // (important to recurse an odd number of times, due to bit flipping)
		for (kv in map) {
			result = result xor kv.key.Hash(recursionDepth-1)
			result = result xor kv.value!!.Hash(recursionDepth-1)
		}
		return result
	}

	override fun Equality(rhs: Value?, recursionDepth: Int): Double {
		if (rhs !is ValMap) return 0.0

		val rhm = rhs.map
		if (rhm == map) return 1.0  // (same map)
		val count = map.size
		if (count != rhm.size) return 0.0
		if (recursionDepth < 1) return 0.5        // in too deep
		var result = 1.0
		for (kv in map) {
			if (!rhm.containsKey(kv.key)) return 0.0
			result *= kv.value!!.Equality(rhm[kv.key], recursionDepth-1)
			if (result <= 0) break
		}
		return result
	}

	override fun CanSetElem() = true

	/// <summary>
	/// Set the value associated with the given key (index).  This is where
	/// we take the opportunity to look for an assignment override function,
	/// and if found, give that a chance to handle it instead.
	/// </summary>
	override fun SetElem(index: Value, value: Value?) {
		if (assignOverride == null || !assignOverride!!(index, value)) {
			map[index] = value
		}
	}

	/// <summary>
	/// Get the indicated key/value pair as another map containing "key" and "value".
	/// (This is used when iterating over a map with "for".)
	/// </summary>
	/// <param name="index">0-based index of key/value pair to get.</param>
	/// <returns>new map containing "key" and "value" with the requested key/value pair</returns>
	fun GetKeyValuePair(index:Int):ValMap {
		val keys = map.keys.toTypedArray()
		if (index < 0 || index >= keys.size) {
			throw IndexException("index $index out of range for map")
		}
		val key = keys[index]    // (TODO: consider more efficient methods here)
		val result = ValMap()
		result.map[keyStr] = key
		result.map[valStr] = map[key]
		return result
	}

	companion object {
		private val keyStr = ValString("key")
		private val valStr = ValString("value")
	}
}

class Function(var code: List<TAC.Line>?) {
	/// <summary>
	/// Param: helper class representing a function parameter.
	/// </summary>
	data class Param(val name: String, val defaultValue: Value?)

	// Function parameters
	val parameters = mutableListOf<Param>()

	fun toString(vm: TAC.Machine?): String {
		val s = StringBuilder()
		s.append("FUNCTION(")
		for (i in 0 until parameters.size) {
			if (i > 0) s.append(", ")
			s.append(parameters[i].name)
			val defaultValue = parameters[i].defaultValue
			if (defaultValue != null) s.append("=" + defaultValue.CodeForm(vm))
		}
		s.append(")")
		return s.toString()
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is Function) return false

		if (code != other.code) return false
		if (parameters != other.parameters) return false

		return true
	}

	override fun hashCode(): Int {
		var result = code?.hashCode() ?: 0
		result = 31 * result + parameters.hashCode()
		return result
	}
}

class ValFunction(val function: Function) : Value() {
	/**
	 * local variables where the function was defined (usually, the module)
	 */
	var moduleVars: ValMap? = null

	override fun toString(vm: TAC.Machine?): String {
		return function.toString(vm)
	}

	// A function value is ALWAYS considered true.
	override fun BoolValue() = true

	override fun IsA(type: Value?, vm:TAC.Machine): Boolean {
		return type == vm.functionType
	}

	override fun Hash(recursionDepth: Int): Int {
		return function.hashCode()
	}

	// Two Function values are equal only if they refer to the exact same function
	override fun Equality(rhs: Value?, recursionDepth: Int) = when (rhs) {
		!is ValFunction -> 0.0
		else -> when (function) {
			rhs.function -> 1.0
			else -> 0.0
		}
	}
}

class ValTemp(var tempNum: Int) : Value() {
	override fun Val(context: TAC.Context): Value? {
		return context.GetTemp(tempNum)
	}

	override fun ValWithLocation(context: TAC.Context): Pair<Value?, ValMap?> {
		return context.GetTemp(tempNum) to null
	}

	override fun toString(vm: TAC.Machine?) = "_$tempNum"

	override fun Hash(recursionDepth:Int) = tempNum.hashCode()

	override fun Equality(rhs: Value?, recursionDepth:Int) =
		if (rhs is ValTemp && rhs.tempNum == tempNum) 1.0 else 0.0
}

class ValVar(val identifier: String) : Value() {
	var noInvoke: Boolean = false    // reflects use of "@" (address-of) operator

	override fun Val(context: TAC.Context): Value? {
		return context.GetVar(identifier)
	}

	override fun ValWithLocation(context: TAC.Context): Pair<Value?, ValMap?> {
		return context.GetVar(identifier) to null
	}

	override fun toString(vm: TAC.Machine?) = when {
		noInvoke -> "@$identifier"
		else -> identifier
	}

	override fun Hash(recursionDepth:Int) = identifier.hashCode()

	override fun Equality(rhs: Value?, recursionDepth:Int) =
		if (rhs is ValVar && rhs.identifier == identifier) 1.0 else 0.0

	companion object {
		// Special name for the implicit result variable we assign to on expression statements:
		val implicitResult = ValVar("_")
	}
}

class ValSeqElem(
	val sequence:Value,
	val index:Value?,
	var noInvoke:Boolean = false	// reflects use of "@" (address-of) operator
) : Value() {
	override fun Val(context:TAC.Context ):Value? {
		return ValWithLocation(context).first
	}

	override fun ValWithLocation(context: TAC.Context):Pair<Value?, ValMap?> {
		var valueFoundIn: ValMap? = null
		val idxVal = index?.Val(context)
		if (idxVal is ValString) return Resolve(sequence, idxVal.value, context)
		// Ok, we're searching for something that's not a string;
		// this can only be done in maps and lists (and lists, only with a numeric index).
		val baseVal = sequence.Val(context)
		if (baseVal is ValMap) {
			val result = baseVal.LookupWithLocation(idxVal!!)
			if (result.second == null) throw KeyException(idxVal.CodeForm(context.vm, 1))
			return result
		} else if (baseVal is ValList && idxVal is ValNumber) {
			return baseVal.GetElem(idxVal) to valueFoundIn
		} else if (baseVal is ValString && idxVal is ValNumber) {
			return baseVal.GetElem(idxVal) to valueFoundIn
		}

		throw TypeException("Type Exception: can't index into this type")
	}

	override fun toString(vm: TAC.Machine?) = "${if (noInvoke) "@" else ""}#sequence[$index]"

	override fun Hash(recursionDepth: Int): Int {
		return sequence.Hash(recursionDepth-1) xor index!!.Hash(recursionDepth-1)
	}

	override fun Equality(rhs: Value?, recursionDepth: Int) =
		if (rhs is ValSeqElem && rhs.sequence == sequence && rhs.index == index) 1.0 else 0.0

	companion object {
		/// <summary>
		/// Look up the given identifier in the given sequence, walking the type chain
		/// until we either find it, or fail.
		/// </summary>
		/// <param name="sequence">Sequence (object) to look in.</param>
		/// <param name="identifier">Identifier to look for.</param>
		/// <param name="context">Context.</param>

		// out ValMap valueFoundIn
		fun Resolve(sequence: Value?, identifier: String, context: TAC.Context): Pair<Value?, ValMap?> {
			var sequence = sequence

			var includeMapType = true
			var valueFoundIn: ValMap? = null

			var loopsLeft = 1000        // (max __isa chain depth)
			while (sequence != null) {
				if (sequence is ValTemp || sequence is ValVar) {
					sequence = sequence.Val(context)
				}
				if (sequence is ValMap) {
					// If the map contains this identifier, return its value.
					var idVal = TempValString.Get(identifier)
					val found = sequence.map.containsKey(idVal)
					TempValString.Release(idVal)
					if (found) {
						valueFoundIn = sequence
						return sequence.map[idVal] to valueFoundIn
					}

					// Otherwise, if we have an __isa, try that next.
					// (Watch out for loops an the __isa chain.)
					if (loopsLeft < 0 || !sequence.map.containsKey(ValString.magicIsA)) {
						// ...and if we don't have an __isa, try the generic map type if allowed
						if (!includeMapType) throw KeyException(identifier)
						sequence = Intrinsics.MapType()
						includeMapType = false
					} else {
						sequence = sequence.map[ValString.magicIsA]
					}
				} else if (sequence is ValList) {
					sequence = context.vm!!.listType ?: Intrinsics.ListType()
					includeMapType = false
				} else if (sequence is ValString) {
					sequence = context.vm!!.stringType ?: Intrinsics.StringType()
					includeMapType = false
				} else if (sequence is ValNumber) {
					sequence = context.vm!!.numberType ?: Intrinsics.NumberType()
					includeMapType = false
				} else if (sequence is ValFunction) {
					sequence = context.vm!!.functionType ?: Intrinsics.FunctionType()
					includeMapType = false
				} else {
					throw TypeException("Type Error (while attempting to look up $identifier)")
				}
				loopsLeft--
			}
			return null to valueFoundIn
		}
	}
}

class RValueEqualityComparer : Comparator<Value> {
	override fun compare(a: Value, b: Value): Int {
		// TODO: Make proper int comparison
		return if (a.Equality(b) > 0) 0 else {
			when {
				a is ValString && b is ValString -> a.value.compareTo(b.value)
				a is ValNumber && b is ValNumber -> a.value.compareTo(b.value)
				else -> a.Hash() - b.Hash()
			}
		}
	}

	companion object {
		val instance = RValueEqualityComparer()
	}
}