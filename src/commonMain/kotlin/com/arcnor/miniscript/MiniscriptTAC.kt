package com.arcnor.miniscript

import kotlin.math.pow

object TAC {
	class Line(var lhs: Value?, val op: Op, var rhsA: Value? = null, val rhsB: Value? = null) {
		var location: SourceLoc? = null

		enum class Op {
			Noop,
			AssignA,
			AssignImplicit,
			APlusB,
			AMinusB,
			ATimesB,
			ADividedByB,
			AModB,
			APowB,
			AEqualB,
			ANotEqualB,
			AGreaterThanB,
			AGreatOrEqualB,
			ALessThanB,
			ALessOrEqualB,
			AisaB,
			AAndB,
			AOrB,
			BindContextOfA,
			CopyA,
			NotA,
			GotoA,
			GotoAifB,
			GotoAifTrulyB,
			GotoAifNotB,
			PushParam,
			CallFunctionA,
			CallIntrinsicA,
			ReturnA,
			ElemBofA,
			ElemBofIterA,
			LengthOfA
		}

		override fun toString(): String {
			val text = when (op) {
				Op.AssignA -> "$lhs := $rhsA"
				Op.AssignImplicit -> "_ := $rhsA"
				Op.APlusB -> "$lhs := $rhsA + $rhsB"
				Op.AMinusB -> "$lhs := $rhsA - $rhsB"
				Op.ATimesB -> "$lhs := $rhsA * $rhsB"
				Op.ADividedByB -> "$lhs := $rhsA / $rhsB"
				Op.AModB -> "$lhs := $rhsA % $rhsB"
				Op.APowB -> "$lhs := $rhsA ^ $rhsB"
				Op.AEqualB -> "$lhs := $rhsA == $rhsB"
				Op.ANotEqualB -> "$lhs := $rhsA != $rhsB"
				Op.AGreaterThanB -> "$lhs := $rhsA > $rhsB"
				Op.AGreatOrEqualB -> "$lhs := $rhsA >= $rhsB"
				Op.ALessThanB -> "$lhs := $rhsA < $rhsB"
				Op.ALessOrEqualB -> "$lhs := $rhsA <= $rhsB"
				Op.AAndB -> "$lhs := $rhsA and $rhsB"
				Op.AOrB -> "$lhs := $rhsA or $rhsB"
				Op.AisaB -> "$lhs := $rhsA isa $rhsB"
				Op.BindContextOfA -> "$rhsA.moduleVars = $rhsB"
				Op.CopyA -> "$lhs := copy of $rhsA"
				Op.NotA -> "$lhs := not $rhsA"
				Op.GotoA -> "goto $rhsA"
				Op.GotoAifB -> "goto $rhsA if $rhsB"
				Op.GotoAifTrulyB -> "goto $rhsA if truly $rhsB"
				Op.GotoAifNotB -> "goto $rhsA if not $rhsB"
				Op.PushParam -> "push param $rhsA"
				Op.CallFunctionA -> "$lhs := call $rhsA with $rhsB args"
				Op.CallIntrinsicA -> "intrinsic ${Intrinsic.GetByID(rhsA!!.IntValue())}"
				Op.ReturnA -> "$lhs := $rhsA; return"
				Op.ElemBofA -> "$lhs = $rhsA[$rhsB]"
				Op.ElemBofIterA -> "$lhs = $rhsA iter $rhsB"
				Op.LengthOfA -> "$lhs = len($rhsA)"
				else -> throw RuntimeException("unknown opcode: $op")
			}
//				if (comment != null) text = text + "\t// " + comment;
			return text
		}


		/// <summary>
		/// Evaluate this line and return the value that would be stored
		/// into the lhs.
		/// </summary>
		fun Evaluate(context: Context): Value? {
			if (op == Op.AssignA || op == Op.ReturnA || op == Op.AssignImplicit) {
				// Assignment is a bit of a special case.  It's EXTREMELY common
				// in TAC, so needs to be efficient, but we have to watch out for
				// the case of a RHS that is a list or map.  This means it was a
				// literal in the source, and may contain references that need to
				// be evaluated now.
				return when (rhsA) {
					is ValList, is ValMap -> rhsA!!.FullEval(context)
					null -> null
					else -> rhsA!!.Val(context)
				}
			} else if (op == Op.CopyA) {
				// This opcode is used for assigning a literal.  We actually have
				// to copy the literal, in the case of a mutable object like a
				// list or map, to ensure that if the same code executes again,
				// we get a new, unique object.
				return when (rhsA) {
					is ValList -> (rhsA as ValList).EvalCopy(context)
					is ValMap -> (rhsA as ValMap).EvalCopy(context)
					null -> null
					else -> rhsA!!.Val(context)
				}
			}

			val opA = rhsA?.Val(context)
			val opB = rhsB?.Val(context)

			if (op == Op.AisaB) {
				if (opA == null) return ValNumber.Truth(opB == null)
				return ValNumber.Truth(opA.IsA(opB, context.vm!!))
			}

			if (op == Op.ElemBofA && opB is ValString) {
				// You can now look for a string in almost anything...
				// and we have a convenient (and relatively fast) method for it:
				return ValSeqElem.Resolve(opA, opB.value, context).first
			}

			if (op == Op.AEqualB && (opA == null || opB == null)) {
				return ValNumber.Truth(opA == opB)
			}
			if (op == Op.ANotEqualB && (opA == null || opB == null)) {
				return ValNumber.Truth(opA != opB)
			}

			if (opA is ValNumber) {
				val fA = opA.value
				when (op) {
					Op.GotoA -> {
						context.lineNum = fA.toInt()
						return null
					}
					Op.GotoAifB -> {
						if (opB != null && opB.BoolValue()) context.lineNum = fA.toInt()
						return null
					}
					Op.GotoAifTrulyB -> {
						// Unlike GotoAifB, which branches if B has any nonzero
						// value (including 0.5 or 0.001), this branches only if
						// B is TRULY true, i.e., its integer value is nonzero.
						// (Used for short-circuit evaluation of "or".)
						var i = 0
						if (opB != null) i = opB.IntValue()
						if (i != 0) context.lineNum = fA.toInt()
						return null
					}
					Op.GotoAifNotB -> {
						if (opB == null || !opB.BoolValue()) context.lineNum = fA.toInt()
						return null
					}
					Op.CallIntrinsicA -> {
						// NOTE: intrinsics do not go through NextFunctionContext.  Instead
						// they execute directly in the current context.  (But usually, the
						// current context is a wrapper function that was invoked via
						// Op.CallFunction, so it got a parameter context at that time.)
						val result = Intrinsic.Execute(fA.toInt(), context, context.partialResult)
						if (result.done) {
							context.partialResult = null
							return result.result
						}
						// OK, this intrinsic function is not yet done with its work.
						// We need to stay on this same line and call it again with
						// the partial result, until it reports that its job is complete.
						context.partialResult = result
						context.lineNum--
						return null
					}
					Op.NotA -> {
						return ValNumber (1.0 - AbsClamp01(fA))
					}
				}
				if (opB is ValNumber || opB == null) {
					var fB = (opB as? ValNumber)?.value ?: 0.0
					when (op) {
						Op.APlusB -> return ValNumber(fA + fB)
						Op.AMinusB -> return ValNumber(fA - fB)
						Op.ATimesB -> return ValNumber(fA * fB)
						Op.ADividedByB -> return ValNumber(fA / fB)
						Op.AModB -> return ValNumber(fA % fB)
						Op.APowB-> return ValNumber(fA.pow(fB))
						Op.AEqualB-> return ValNumber.Truth(fA == fB)
						Op.ANotEqualB-> return ValNumber.Truth(fA != fB)
						Op.AGreaterThanB-> return ValNumber.Truth(fA > fB)
						Op.AGreatOrEqualB-> return ValNumber.Truth(fA >= fB)
						Op.ALessThanB-> return ValNumber.Truth(fA < fB)
						Op.ALessOrEqualB-> return ValNumber.Truth(fA <= fB)
						Op.AAndB -> {
							if (opB !is ValNumber) fB = if (opB != null && opB.BoolValue()) 1.0 else 0.0

							return ValNumber (Clamp01(fA * fB))
						}
						Op.AOrB -> {
							if (opB !is ValNumber) fB = if (opB != null && opB.BoolValue()) 1.0 else 0.0

							return ValNumber (Clamp01(fA + fB - fA * fB))
						}
					}
				} else if (opB is ValString) {
					// number (op) string
					val sA = opA.toString()
					val sB = opB.toString()
					when (op) {
						Op.APlusB -> return ValString(sA + sB)
					}
				}
			} else if (opA is ValString) {
				var sA = opA.value
				if (op == Op.ATimesB || op == Op.ADividedByB) {
					var factor = 0.0
					factor = if (op == Op.ATimesB) {
						opB as? ValNumber ?: throw TypeException("wanted number (string replication)")
						opB.value
					} else {
						opB as? ValNumber ?: throw TypeException("wanted number (string division)")
						1.0 / opB.value
					}
					val repeats = factor.toInt()
					if (repeats < 0) return ValString.empty
					if (repeats * sA.length > ValString.maxSize) throw LimitExceededException("string too large")
					val result = StringBuilder()
					for (i in 0 until repeats) {
						result.append(sA)
					}
					val extraChars = (sA.length * (factor - repeats)).toInt()
					if (extraChars > 0) result.append(sA.substring(0, extraChars))
					return ValString(result.toString())
				}
				if (op == Op.ElemBofA || op == Op.ElemBofIterA) {
					var idx = opB!!.IntValue()
					Check.Range(idx, -sA.length, sA.length - 1, "string index")
					if (idx < 0) idx += sA.length
					return ValString(sA.substring(idx, idx + 1))
				}
				val sB = opB?.toString(context.vm)
				when (op) {
					Op.APlusB -> {
						if (opB == null) return opA
						if (sA.length + sB!!.length > ValString.maxSize) throw LimitExceededException("string too large")
						return ValString(sA + sB)
					}
					Op.AMinusB -> {
						if (opB == null) return opA
						if (sA.endsWith(sB!!)) sA = sA.substring(0, sA.length - sB.length)
						return ValString(sA)
					}
					Op.NotA -> return ValNumber.Truth(sA.isNullOrEmpty())
					Op.AEqualB -> return ValNumber.Truth(sA == sB)
					Op.ANotEqualB -> return ValNumber.Truth(sA != sB)
					Op.AGreaterThanB -> return ValNumber.Truth(sA > sB!!)
					Op.AGreatOrEqualB -> return ValNumber.Truth(sA >= sB!!)
					Op.ALessThanB -> return ValNumber.Truth(sA < sB!!)
					Op.ALessOrEqualB -> return ValNumber.Truth(sA <= sB!!)
					Op.LengthOfA -> return ValNumber(sA.length.toDouble())
				}
			} else if (opA is ValList) {
				val list = opA.values
				if (op == Op.ElemBofA || op == Op.ElemBofIterA) {
					// list indexing
					var idx = opB!!.IntValue()
					Check.Range(idx, -list.size, list.size - 1, "list index")
					if (idx < 0) idx += list.size
					return list[idx]
				} else if (op == Op.LengthOfA) {
					return ValNumber(list.size.toDouble())
				} else if (op == Op.AEqualB) {
					return ValNumber.Truth(opA.Equality(opB))
				} else if (op == Op.ANotEqualB) {
					return ValNumber.Truth(1.0 - opA.Equality(opB))
				} else if (op == Op.APlusB) {
					// list concatenation
					opB as? ValList ?: throw TypeException("wanted list (list concatenation)")
					val list2 = opB.values
					if (list.size + list2.size > ValList.maxSize) throw LimitExceededException("list too large")
					val result = mutableListOf<Value?>()
					for (v in list) result.add(context.ValueInContext(v))
					for (v in list2) result.add(context.ValueInContext(v))
					return ValList(result)
				} else if (op == Op.ATimesB || op == Op.ADividedByB) {
					// list replication (or division)
					var factor = 0.0
					if (op == Op.ATimesB) {
						opB as? ValNumber ?: throw TypeException("wanted number (list replication)")
						factor = opB.value
					} else {
						opB as? ValNumber ?: throw TypeException("wanted number (list division)")
						factor = 1.0 / opB.value
					}
					if (factor <= 0) return ValList()
					val finalCount = (list.size * factor).toInt()
					if (finalCount > ValList.maxSize) throw LimitExceededException("list too large")
					val result = mutableListOf<Value?>()
					for (i in 0 until finalCount) {
						result.add(list[i % list.size])
					}
					return ValList(result)
				} else if (op == Op.NotA) {
					return ValNumber.Truth(!opA.BoolValue())
				}
			} else if (opA is ValMap) {
				if (op == Op.ElemBofA) {
					// map lookup
					// (note, cases where opB is a string are handled above, along with
					// all the other types; so we'll only get here for non-string cases)
					val se = ValSeqElem(opA, opB)
					return se.Val(context)
					// (This ensures we walk the "__isa" chain in the standard way.)
				} else if (op == Op.ElemBofIterA) {
					// With a map, ElemBofIterA is different from ElemBofA.  This one
					// returns a mini-map containing a key/value pair.
					return opA.GetKeyValuePair(opB!!.IntValue())
				} else if (op == Op.LengthOfA) {
					return ValNumber(opA.Count.toDouble())
				} else if (op == Op.AEqualB) {
					return ValNumber.Truth(opA.Equality(opB))
				} else if (op == Op.ANotEqualB) {
					return ValNumber.Truth(1.0 - opA.Equality(opB))
				} else if (op == Op.APlusB) {
					// map combination
					val map = opA.map
					opB as? ValMap ?: throw TypeException("wanted map (map combination)")
					val map2 = opB.map
					val result = ValMap()
					for (kv in map) result.map[kv.key] = context.ValueInContext(kv.value)
					for (kv in map2) result.map[kv.key] = context.ValueInContext(kv.value)
					return result
				} else if (op == Op.NotA) {
					return ValNumber.Truth(!opA.BoolValue())
				}
			} else if (opA is ValFunction && opB is ValFunction) {
				val fA = opA.function
				val fB = opB.function
				when (op) {
					Op.AEqualB -> return ValNumber.Truth(fA == fB)
					Op.ANotEqualB -> return ValNumber.Truth(fA != fB)
				}
			} else {
				// opA is something else... perhaps null
				when (op) {
					Op.BindContextOfA -> {
						if (context.variables == null) context.variables = ValMap()
						val valFunc = opA as ValFunction
						valFunc.moduleVars = context.variables
						return null
					}
					Op.NotA -> {
						return if (opA != null && opA.BoolValue()) ValNumber.zero else ValNumber.one
					}
				}
			}


			if (op == Op.AAndB || op == Op.AOrB) {
				// We already handled the case where opA was a number above;
				// this code handles the case where opA is something else.
				val fA = if (opA != null && opA.BoolValue()) 1.0 else 0.0
				val fB = if (opB is ValNumber) opB.value
				else if (opB != null && opB.BoolValue()) 1.0 else 0.0
				val result = if (op == Op.AAndB) {
					fA * fB
				} else {
					1.0 - (1.0 - AbsClamp01(fA)) * (1.0 - AbsClamp01(fB))
				}
				return ValNumber(result)
			}
			return null
		}

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Line) return false

			if (lhs != other.lhs) return false
			if (op != other.op) return false
			if (rhsA != other.rhsA) return false
			if (rhsB != other.rhsB) return false
			if (location != other.location) return false

			return true
		}

		override fun hashCode(): Int {
			var result = lhs?.hashCode() ?: 0
			result = 31 * result + op.hashCode()
			result = 31 * result + (rhsA?.hashCode() ?: 0)
			result = 31 * result + (rhsB?.hashCode() ?: 0)
			result = 31 * result + (location?.hashCode() ?: 0)
			return result
		}


		companion object {
			fun Clamp01(d: Double) = when {
				d < 0 -> 0.0
				d > 1 -> 1.0
				else -> d
			}

			fun AbsClamp01(d: Double): Double {
				var d = d
				if (d < 0) d = -d
				return if (d > 1) 1.0 else d
			}
		}
	}

	class Context(
		/**
		 * TAC lines we're executing
		 */
		val code: List<Line>?,
		/**
		 * where to store the return value (in the calling context)
		 */
		var resultStorage: Value?,
		/**
		 * parent (calling) context
		 */
		var parent: Context?,
		/**
		 * virtual machine
		 */
		var vm: Machine?
	) {
		var lineNum: Int = 0                // next line to be executed
		var variables: ValMap? = null        // local variables for this call frame
		var moduleVars: ValMap? = null        // variables of the context where this function was defined
		var args: MutableList<Value?>? = null        // pushed arguments for upcoming calls
		var partialResult: Intrinsic.Result? = null    // work-in-progress of our current intrinsic
		var implicitResultCounter: Int = 0    // how many times we have stored an implicit result

		val done: Boolean
			get() = lineNum >= code!!.size

		val root :Context
			get() {
				var c = this
				while (c.parent != null) c = c.parent!!
				return c
			}

		val interpreter : Interpreter?
			get() {
				if (vm == null || vm!!.interpreter == null) return null
				return vm!!.interpreter!!.get() as Interpreter
			}

		private var temps : MutableList<Value?>? = null            // values of temporaries; temps[0] is always return value

		/// <summary>
		/// Reset this context to the first line of code, clearing out any
		/// temporary variables, and optionally clearing out all variables.
		/// </summary>
		/// <param name="clearVariables">if true, clear our local variables</param>
		fun Reset(clearVariables: Boolean=true) {
			lineNum = 0
			temps = null
			if (clearVariables) variables = ValMap()
		}

		fun JumpToEnd() {
			lineNum = code!!.size
		}

		fun SetTemp(tempNum: Int, value: Value?) {
			if (temps == null) temps = mutableListOf()
			while (temps!!.size <= tempNum) temps!!.add(null)
			temps!![tempNum] = value
		}

		fun GetTemp(tempNum: Int) = temps?.get(tempNum)

		fun GetTemp(tempNum: Int, defaultValue: Value?): Value? {
			if (temps != null && tempNum < temps!!.size) return temps!![tempNum]
			return defaultValue
		}

		fun SetVar(identifier: String, value: Value?) {
			if (identifier == "globals" || identifier == "locals") {
				throw MSRuntimeException("can't assign to $identifier")
			}
			if (variables == null) variables = ValMap()
			variables!![identifier] = value
		}

		/// <summary>
		/// Get the value of a variable available in this context (including
		/// locals, globals, and intrinsics).  Raise an exception if no such
		/// identifier can be found.
		/// </summary>
		/// <param name="identifier">name of identifier to look up</param>
		/// <returns>value of that identifier</returns>
		fun GetVar(identifier: String): Value? {
			// check for special built-in identifiers 'locals' and 'globals'
			if (identifier == "locals") {
				if (variables == null) variables = ValMap()
				return variables
			}
			if (identifier == "globals") {
				if (root.variables == null) root.variables = ValMap()
				return root.variables
			}

			// check for a local variable
			if (variables != null && variables!!.ContainsKey(identifier)) {
				return variables!![identifier]
			}

			// check for a module variable
			if (moduleVars != null && moduleVars!!.ContainsKey(identifier)) {
				return moduleVars!![identifier]
			}

			// OK, we don't have a local or module variable with that name.
			// Check the global scope (if that's not us already).
			if (parent != null) {
				val globals = root
				if (globals.variables != null && globals.variables!!.ContainsKey(identifier)) {
					return globals.variables!![identifier]
				}
			}

			// Finally, check intrinsics.
			val intrinsic = Intrinsic.GetByName(identifier)
			if (intrinsic != null) return intrinsic.GetFunc()

			// No luck there either?  Undefined identifier.
			throw UndefinedIdentifierException(identifier)
		}

		fun StoreValue(lhs: Value?, value: Value?) {
			if (lhs is ValTemp) {
				SetTemp(lhs.tempNum, value)
			} else if (lhs is ValVar) {
				SetVar(lhs.identifier, value)
			} else if (lhs is ValSeqElem) {
				val seqElem = lhs
				val seq = seqElem.sequence.Val(this) ?: throw MSRuntimeException("can't set indexed element of null")
				if (!seq.CanSetElem()) {
					throw MSRuntimeException("can't set an indexed element in this type")
				}
				var index = seqElem.index
				if (index is ValVar || index is ValSeqElem ||
					index is ValTemp) index = index.Val(this)
				seq.SetElem(index!!, value)
			} else {
				if (lhs != null) throw MSRuntimeException("not an lvalue")
			}
		}

		fun ValueInContext(value: Value?): Value? {
			if (value == null) return null
			return value.Val(this)
		}

		/// <summary>
		/// Store a parameter argument in preparation for an upcoming call
		/// (which should be executed in the context returned by NextCallContext).
		/// </summary>
		/// <param name="arg">Argument.</param>
		fun PushParamArgument(arg: Value?) {
			if (args == null) args = mutableListOf()
			args!!.add(arg)
		}

		/// <summary>
		/// Get a context for the next call, which includes any parameter arguments
		/// that have been set.
		/// </summary>
		/// <returns>The call context.</returns>
		/// <param name="func">Function to call.</param>
		/// <param name="argCount">How many arguments to pop off the stack.</param>
		/// <param name="gotSelf">Whether this method was called with dot syntax.</param>
		/// <param name="resultStorage">Value to stuff the result into when done.</param>
		fun NextCallContext(func: Function, argCount: Int, gotSelf: Boolean, resultStorage: Value?): Context {
			val result = Context(
				func.code,
				resultStorage = resultStorage,
				parent = this,
				vm = vm
			)

			// Stuff arguments, stored in our 'args' stack,
			// into local variables corrersponding to parameter names.
			// As a special case, skip over the first parameter if it is named 'self'
			// and we were invoked with dot syntax.
			val selfParam: Int = if (gotSelf && func.parameters.size > 0 && func.parameters[0].name == "self") 1 else 0
			for (i in 0 until argCount) {
				// Careful -- when we pop them off, they're in reverse order.
				val argument = args!!.removeAt(args!!.size - 1)
				val paramNum = argCount - 1 - i + selfParam
				if (paramNum >= func.parameters.size) {
					throw TooManyArgumentsException()
				}
				result.SetVar(func.parameters[paramNum].name, argument)
			}
			// And fill in the rest with default values
			for (paramNum in (argCount+selfParam) until func.parameters.size) {
				result.SetVar(func.parameters[paramNum].name, func.parameters[paramNum].defaultValue)
			}

			return result
		}

		fun Dump() {
			println("CODE:")
			for (i in 0 until code!!.size) {
				val x = if (i == lineNum) ">" else " "
				println("$x $i: ${code[i]}")
			}

			println("\nVARS:")
			if (variables == null) {
				println(" NONE")
			} else {
				for (v in variables!!.Keys) {
					val id = v.toString(vm)
					println("$id: ${variables!![id]!!.toString(vm)}")
				}
			}

			println("\nTEMPS:")
			if (temps == null) {
				println(" NONE")
			} else {
				for (i in 0 until temps!!.size) {
					println("_$i: ${temps!![i]}")
				}
			}
		}

		override fun toString() = "Context[$lineNum/${code!!.size}]"
	}

	class Machine(
		/**
		 * contains global variables
		 */
		val globalContext: Context,
		/**
		 * where print() results should go
		 */
		standardOutput: TextOutputMethod? = null
		) {
		var interpreter: WeakReference<*>? = null        // interpreter hosting this machine
		var storeImplicit = false        // whether to store implicit values (e.g. for REPL)
		var yielding = false            // set to true by yield intrinsic
		var functionType: ValMap? = null
		var listType: ValMap? = null
		var mapType: ValMap? = null
		var numberType: ValMap? = null
		var stringType: ValMap? = null
		var versionMap: ValMap? = null

		var standardOutput = standardOutput ?: ::println

		val done: Boolean
			get() = (stack.size <= 1 && stack.peek().done)

		val runTime: Double
			get() { return if (stopwatch == null) 0.0 else stopwatch!!.ElapsedSeconds; }

		private var stack = mutableListOf<Context>()
		private var stopwatch: Stopwatch? = null

		init {
			globalContext.vm = this
			stack.push(globalContext)
		}

		fun Stop() {
			while (stack.size > 1) stack.pop()
			stack.peek().JumpToEnd()
		}

		fun Reset() {
			while (stack.size > 1) stack.pop()
			stack.peek().Reset(false)
		}

		fun Step() {
			if (stack.isEmpty()) return        // not even a global context

			if (stopwatch == null) {
				stopwatch = Stopwatch()
				stopwatch!!.Start()
			}

			var context = stack.peek()
			while (context.done) {
				if (stack.size === 1) return    // all done (can't pop the global context)
				PopContext()
				context = stack.peek()
			}

			val line = context.code!![context.lineNum++]
			try {
				DoOneLine(line, context)
			} catch (mse: MiniscriptException) {
				mse.location = line.location
				throw mse
			}
		}

		/// <summary>
		/// Directly invoke a ValFunction by manually pushing it onto the call stack.
		/// This might be useful, for example, in invoking handlers that have somehow
		/// been registered with the host app via intrinsics.
		/// </summary>
		/// <param name="func">Miniscript function to invoke</param>
		/// <param name="resultStorage">where to store result of the call, in the calling context</param>
		fun ManuallyPushCall(func: ValFunction, resultStorage: Value?=null) {
			var argCount = 0
			var self = null    // "self" is always null for a manually pushed call
			val nextContext = stack.peek().NextCallContext(func.function, argCount, self != null, null)
			if (self != null) nextContext.SetVar("self", self)
			nextContext.resultStorage = resultStorage
			stack.push(nextContext)
		}


		private fun DoOneLine(line: Line, context: Context) {
//				Console.WriteLine("EXECUTING line " + (context.lineNum-1) + ": " + line);
			val lineRhsA = line.rhsA
			when (line.op) {
				Line.Op.PushParam -> {
					val value = context.ValueInContext(lineRhsA)
					context.PushParamArgument(value)
				}
				Line.Op.CallFunctionA -> {
					// Resolve rhsA.  If it's a function, invoke it; otherwise,
					// just store it directly.
					val (funcVal, valueFoundIn) = lineRhsA!!.ValWithLocation(context)    // resolves the whole dot chain, if any
					if (funcVal is ValFunction) {
						var self: Value? = null
						// bind "super" to the parent of the map the function was found in
						val superValue = valueFoundIn?.Lookup(ValString.magicIsA)
						if (lineRhsA is ValSeqElem) {
							// bind "self" to the object used to invoke the call, except
							// when invoking via "super"
							val seq = lineRhsA.sequence
							self = when {
								seq is ValVar && seq.identifier == "super" -> context.GetVar("self")
								else -> context.ValueInContext(seq)
							}
						}
						val func = funcVal
						val argCount = line.rhsB!!.IntValue()
						val nextContext = context.NextCallContext(func.function, argCount, self != null, line.lhs)
						nextContext.moduleVars = func.moduleVars
						if (valueFoundIn != null) nextContext.SetVar("super", superValue)
						if (self != null) nextContext.SetVar("self", self)    // (set only if bound above)
						stack.push(nextContext)
					} else {
						context.StoreValue(line.lhs, funcVal)
					}
				}
				Line.Op.ReturnA -> {
					val value = line.Evaluate(context)
					context.StoreValue(line.lhs, value)
					PopContext()
				}
				Line.Op.AssignImplicit -> {
					val value = line.Evaluate(context)
					if (storeImplicit) {
						context.StoreValue(ValVar.implicitResult, value)
						context.implicitResultCounter++
					}
				}
				else -> {
					val value = line.Evaluate(context)
					context.StoreValue(line.lhs, value)
				}
			}
		}

		private fun PopContext() {
			// Our top context is done; pop it off, and copy the return value in temp 0.
			if (stack.size == 1) return    // down to just the global stack (which we keep)
			val context = stack.pop()
			val result = context.GetTemp(0, null)
			val storage = context.resultStorage

			stack.peek().StoreValue(storage, result)
		}

		fun GetTopContext() = stack.peek()

		fun DumpTopContext() {
			stack.peek().Dump()
		}

		fun FindShortName(value: Value): String? {
			if (globalContext == null || globalContext.variables == null) return null

			for (kv in globalContext.variables!!.map) {
				if (kv.value == value && kv.key != value) return kv.key.toString(this)
			}

			return Intrinsic.shortNames.getOrElse(value, { null })
		}
	}

	fun Dump(lines: List<Line>) {
		var lineNum = 0
		for (line in lines) {
			println("${lineNum++}. $line");
		}
	}

	fun LTemp(tempNum: Int) = ValTemp(tempNum)

	fun LVar(identifier: String) = ValVar(identifier)

	fun RTemp(tempNum:Int) = ValTemp(tempNum)

	fun Num(value:Double) = ValNumber(value)
	fun Num(value:Int) = ValNumber(value.toDouble())

	fun Str(value:String) = ValString(value)
	fun Str(value:Char) = ValString(value.toString())

	fun IntrinsicByName(name:String) = ValNumber(Intrinsic.GetByName(name)!!.id.toDouble())
}
