package com.arcnor.miniscript

typealias TextOutputMethod = (output: String) -> Unit

open class Interpreter(
	val source: String? = null,
	standardOutput: TextOutputMethod? = null,
	errorOutput: TextOutputMethod? = null
) {
	private var _standardOutput: TextOutputMethod
	/// <summary>
	/// errorOutput: receives error messages from the runtime.  (This happens
	/// via the ReportError method, which is virtual; so if you want to catch
	/// the actual exceptions rather than get the error messages as strings,
	/// you can subclass Interpreter and override that method.)
	/// </summary>
	private var _errorOutput: TextOutputMethod


	/// <summary>
	/// implicitOutput: receives the value of expressions entered when
	/// in REPL mode.  If you're not using the REPL() method, you can
	/// safely ignore this.
	/// </summary>
	var implicitOutput: TextOutputMethod? = null

	/// <summary>
	/// hostData is just a convenient place for you to attach some arbitrary
	/// data to the interpreter.  It gets passed through to the context object,
	/// so you can access it inside your custom intrinsic functions.  Use it
	/// for whatever you like (or don't, if you don't feel the need).
	/// </summary>
	var hostData: Any? = null

	/// <summary>
	/// done: returns true when we don't have a virtual machine, or we do have
	/// one and it is done (has reached the end of its code).
	/// </summary>
	val done: Boolean
		get() {
			return vm == null || vm!!.done;
		}

	/// <summary>
	/// vm: the virtual machine this interpreter is running.  Most applications will
	/// not need to use this, but it's provided for advanced users.
	/// </summary>
	var vm: TAC.Machine? = null

	/// <summary>
	/// standardOutput: receives the output of the "print" intrinsic.
	/// </summary>
	var standardOutput : TextOutputMethod
		get() {
			return _standardOutput;
		}
		set(value) {
			_standardOutput = value;
			if (vm != null) vm!!.standardOutput = value;
		}

	private var parser: Parser? = null

	init {
		_standardOutput = standardOutput ?: ::println
		_errorOutput = errorOutput ?: ::println
	}

	/// <summary>
	/// Constructor taking source code in the form of a list of strings.
	/// </summary>
	constructor(source: List<String>,
	            standardOutput: TextOutputMethod? = null,
	            errorOutput: TextOutputMethod? = null) : this(source.joinToString("\n"), standardOutput, errorOutput)

	/// <summary>
	/// Constructor taking source code in the form of a string array.
	/// </summary>
	constructor(source: Array<String>,
	            standardOutput: TextOutputMethod? = null,
	            errorOutput: TextOutputMethod? = null) : this(source.joinToString("\n"), standardOutput, errorOutput)

	/// <summary>
	/// Stop the virtual machine, and jump to the end of the program code.
	/// Also reset the parser, in case it's stuck waiting for a block ender.
	/// </summary>
	fun Stop() {
		if (vm != null) vm!!.Stop()
		if (parser != null) parser!!.PartialReset()
	}


	/// <summary>
	/// Compile our source code, if we haven't already done so, so that we are
	/// either ready to run, or generate compiler errors (reported via errorOutput).
	/// </summary>
	fun Compile() {
		if (vm != null) return    // already compiled

		if (parser == null) parser = Parser()
		try {
			parser!!.Parse(source!!)
			vm = parser!!.CreateVM(standardOutput)
			vm!!.interpreter = WeakReference(this)
		} catch (mse: MiniscriptException) {
			ReportError(mse)
		}

	}


	/// <summary>
	/// Reset the virtual machine to the beginning of the code.  Note that this
	/// does *not* reset global variables; it simply clears the stack and jumps
	/// to the beginning.  Useful in cases where you have a short script you
	/// want to run over and over, without recompiling every time.
	/// </summary>
	fun Restart() {
		if (vm != null) vm!!.Reset()
	}


	/// <summary>
	/// Run the compiled code until we either reach the end, or we reach the
	/// specified time limit.  In the latter case, you can then call RunUntilDone
	/// again to continue execution right from where it left off.
	///
	/// Or, if returnEarly is true, we will also return if we reach an intrinsic
	/// method that returns a partial result, indicating that it needs to wait
	/// for something.  Again, call RunUntilDone again later to continue.
	///
	/// Note that this method first compiles the source code if it wasn't compiled
	/// already, and in that case, may generate compiler errors.  And of course
	/// it may generate runtime errors while running.  In either case, these are
	/// reported via errorOutput.
	/// </summary>
	/// <param name="timeLimit">maximum amout of time to run before returning, in seconds</param>
	/// <param name="returnEarly">if true, return as soon as we reach an intrinsic that returns a partial result</param>
	fun RunUntilDone(timeLimit: Double = 60.0, returnEarly: Boolean = true) {
		try {
			if (vm == null) {
				Compile();
				if (vm == null) return;	// (must have been some error)
			}
			val localVm = vm!!

			val startTime = localVm.runTime;
			localVm.yielding = false;
			while (!localVm.done && !localVm.yielding) {
				if (localVm.runTime - startTime > timeLimit) return;	// time's up for now!
				localVm.Step();		// update the machine
				if (returnEarly && localVm.GetTopContext().partialResult != null) return;	// waiting for something
			}
		} catch (mse:MiniscriptException) {
			ReportError(mse);
			vm!!.GetTopContext().JumpToEnd();
		}
	}


	/// <summary>
	/// Run one step of the virtual machine.  This method is not very useful
	/// except in special cases; usually you will use RunUntilDone (above) instead.
	/// </summary>
	fun Step() {
		try {
			Compile()
			vm!!.Step()
		} catch (mse: MiniscriptException) {
			ReportError(mse)
			vm!!.GetTopContext().JumpToEnd()
		}
	}


	/// <summary>
	/// Read Eval Print Loop.  Run the given source until it either terminates,
	/// or hits the given time limit.  When it terminates, if we have new
	/// implicit output, print that to the implicitOutput stream.
	/// </summary>
	/// <param name="sourceLine">Source line.</param>
	/// <param name="timeLimit">Time limit.</param>
	fun REPL(sourceLine: String?, timeLimit: Double = 60.0) {
		if (parser == null) parser = Parser();
		var localVm = vm
		if (localVm == null) {
			vm = parser!!.CreateVM(standardOutput);
			localVm = vm!!
			localVm.interpreter = WeakReference(this);
		}
		if (sourceLine == "#DUMP") {
			localVm.DumpTopContext();
			return;
		}

		val startTime = localVm.runTime;
		val startImpResultCount = localVm.globalContext.implicitResultCounter;
		localVm.storeImplicit = implicitOutput != null

		try {
			if (sourceLine != null) parser!!.Parse(sourceLine, true);
			if (!parser!!.NeedMoreInput()) {
				while (!localVm.done) {
					if (localVm.runTime - startTime > timeLimit) return;	// time's up for now!
					localVm.Step();
				}
				if (implicitOutput != null && localVm.globalContext.implicitResultCounter > startImpResultCount) {
					val result = localVm.globalContext.GetVar(ValVar.implicitResult.identifier);
					if (result != null) {
						implicitOutput!!.invoke(result.toString(localVm));
					}
				}
			}

		} catch (mse: MiniscriptException) {
			ReportError(mse);
			// Attempt to recover from an error by jumping to the end of the code.
			localVm.GetTopContext().JumpToEnd();
		}
	}


	/// <summary>
	/// Report whether the virtual machine is still running, that is,
	/// whether it has not yet reached the end of the program code.
	/// </summary>
	/// <returns></returns>
	fun Running(): Boolean {
		return vm != null && !vm!!.done
	}


	/// <summary>
	/// Return whether the parser needs more input, for example because we have
	/// run out of source code in the middle of an "if" block.  This is typically
	/// used with REPL for making an interactive console, so you can change the
	/// prompt when more input is expected.
	/// </summary>
	/// <returns></returns>
	fun NeedMoreInput(): Boolean {
		return parser != null && parser!!.NeedMoreInput()
	}

	/// <summary>
	/// Get a value from the global namespace of this interpreter.
	/// </summary>
	/// <param name="varName">name of global variable to get</param>
	/// <returns>Value of the named variable, or null if not found</returns>
	fun GetGlobalValue(varName: String): Value? {
		if (vm == null) return null;
		val c = vm!!.globalContext ?: return null;
		return try {
			c.GetVar(varName);
		} catch (e: UndefinedIdentifierException) {
			null;
		}
	}


	/// <summary>
	/// Set a value in the global namespace of this interpreter.
	/// </summary>
	/// <param name="varName">name of global variable to set</param>
	/// <param name="value">value to set</param>
	fun SetGlobalValue(varName: String, value: Value) {
		if (vm != null) vm!!.globalContext.SetVar(varName, value)
	}

	/// <summary>
	/// Report a MiniScript error to the user.  The default implementation
	/// simply invokes errorOutput with the error description.  If you want
	/// to do something different, then make an Interpreter subclass, and
	/// override this method.
	/// </summary>
	/// <param name="mse">exception that was thrown</param>
	protected open fun ReportError(mse: MiniscriptException) {
		_errorOutput.invoke(mse.Description());
	}
}