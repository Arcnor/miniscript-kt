package com.arcnor.miniscript

import com.arcnor.miniscript.MiniscriptLexer.Lexer
import com.arcnor.miniscript.MiniscriptLexer.Token

class Parser {
	/**
	 * BackPatch: represents a place where we need to patch the code to fill
	 * in a jump destination (once we figure out where that destination is).
	 */
	internal data class BackPatch(
		/**
		 * which code line to patch
		 */
		var lineNum: Int = 0,
		/**
		 * what keyword we're waiting for (e.g., "end if")
		 */
		var waitingFor: String
	)

	/**
	 * Represents a place in the code we will need to jump to later
	 * (typically, the top of a loop of some sort).
	 */
	internal data class JumpPoint(
		/**
		 * line number to jump to
		 */
		var lineNum: Int = 0,
		/**
		 * jump type, by keyword: "while", "for", etc.
		 */
		var keyword: String
	)

	class ParseState {
		val code = mutableListOf<TAC.Line>()
		internal val backpatches = mutableListOf<BackPatch>();
		internal val jumpPoints = mutableListOf<JumpPoint>();
		var nextTempNum = 0;

		fun add(line: TAC.Line) {
			code.add(line)
		}

		/// <summary>
		/// Add the last code line as a backpatch point, to be patched
		/// (in rhsA) when we encounter a line with the given waitFor.
		/// </summary>
		/// <param name="waitFor">Wait for.</param>
		internal fun AddBackpatch(waitFor: String) {
			backpatches.add(BackPatch(lineNum = code.size - 1, waitingFor = waitFor));
		}

		internal fun AddJumpPoint(jumpKeyword: String) {
			jumpPoints.add(JumpPoint(lineNum = code.size, keyword = jumpKeyword));
		}

		internal fun CloseJumpPoint(keyword:String): JumpPoint {
			val idx = jumpPoints.size - 1;
			if (idx < 0 || jumpPoints[idx].keyword != keyword) {
				throw CompilerException("'end $keyword' without matching '$keyword'");
			}
			val result = jumpPoints[idx];
			jumpPoints.removeAt(idx);
			return result;
		}

		// Return whether the given line is a jump target.
		internal fun IsJumpTarget(lineNum:Int) : Boolean {
			for (i in 0 until code.size) {
				val op = code[i].op;
				if ((op == TAC.Line.Op.GotoA || op == TAC.Line.Op.GotoAifB
							|| op == TAC.Line.Op.GotoAifNotB || op == TAC.Line.Op.GotoAifTrulyB)
					&& code[i].rhsA is ValNumber && code[i].rhsA!!.IntValue() == lineNum
				) return true;
			}
			for (i in 0 until jumpPoints.size) {
				if (jumpPoints[i].lineNum == lineNum) return true;
			}
			return false;
		}

		/// <summary>
		/// Call this method when we've found an 'end' keyword, and want
		/// to patch up any jumps that were waiting for that.  Patch the
		/// matching backpatch (and any after it) to the current code end.
		/// </summary>
		/// <param name="keywordFound">Keyword found.</param>
		/// <param name="reservingLines">Extra lines (after the current position) to patch to.</param>
		fun Patch(keywordFound:String, reservingLines:Int=0) {
			Patch(keywordFound, false, reservingLines);
		}

		/// <summary>
		/// Call this method when we've found an 'end' keyword, and want
		/// to patch up any jumps that were waiting for that.  Patch the
		/// matching backpatch (and any after it) to the current code end.
		/// </summary>
		/// <param name="keywordFound">Keyword found.</param>
		/// <param name="alsoBreak">If true, also patch "break"; otherwise skip it.</param>
		/// <param name="reservingLines">Extra lines (after the current position) to patch to.</param>
		fun Patch(keywordFound: String, alsoBreak: Boolean, reservingLines: Int = 0) {
			val target = TAC.Num(code.size + reservingLines);
			var done = false;
			var idx = backpatches.size - 1
			while (idx >= 0 && !done) {
				var patchIt = false
				when (backpatches[idx].waitingFor) {
					keywordFound -> {
						done = true
						patchIt = done
					}
					"break" -> // Not the expected keyword, but "break"; this is always OK,
						// but we may or may not patch it depending on the call.
						patchIt = alsoBreak
					else -> // Not the expected patch, and not "break"; we have a mismatched block start/end.
						throw CompilerException("'" + keywordFound + "' skips expected '" + backpatches[idx].waitingFor + "'")
				}
				if (patchIt) {
					code[backpatches[idx].lineNum].rhsA = target
					backpatches.removeAt(idx)
				}
				idx--
			}

			// Make sure we found one...
			if (!done) throw CompilerException("'$keywordFound' without matching block starter");
		}

		/// <summary>
		/// Patches up all the branches for a single open if block.  That includes
		/// the last "else" block, as well as one or more "end if" jumps.
		/// </summary>
		fun PatchIfBlock() {
			val target = TAC.Num(code.size.toDouble());

			var idx = backpatches.size - 1;
			while (idx >= 0) {
				val bp = backpatches[idx];
				when (bp.waitingFor) {
					"if:MARK" -> {
						// There's the special marker that indicates the true start of this if block.
						backpatches.removeAt(idx);
						return;
					}
					"end if", "else" -> {
						code[bp.lineNum].rhsA = target;
						backpatches.removeAt(idx);
					}
				}
				idx--;
			}
		}
	}

	var errorContext: String? = null    // name of file, etc., used for error reporting

	// Partial input, in the case where line continuation has been used.
	var partialInput: String? = null

	// List of open code blocks we're working on (while compiling a function,
	// we push a new one onto this stack, compile to that, and then pop it
	// off when we reach the end of the function).
	lateinit var outputStack: MutableList<ParseState>

	// Handy reference to the top of outputStack.
	lateinit var output: ParseState

	// A new parse state that needs to be pushed onto the stack, as soon as we
	// finish with the current line we're working on:
	var pendingState: ParseState? = null

	init {
		Reset()
	}


	/// <summary>
	/// Completely clear out and reset our parse state, throwing out
	/// any code and intermediate results.
	/// </summary>
	fun Reset() {
		output = ParseState()
		if (!::outputStack.isInitialized)
			outputStack = mutableListOf()
		else
			outputStack.clear()
		outputStack.push(output)
	}


	/// <summary>
	/// Partially reset, abandoning backpatches, but keeping already-
	/// compiled code.  This would be used in a REPL, when the user
	/// may want to reset and continue after a botched loop or function.
	/// </summary>
	fun PartialReset() {
		if (!::outputStack.isInitialized) outputStack = mutableListOf()
		while (outputStack.size > 1) outputStack.pop()
		output = outputStack.peek()
		output.backpatches.clear()
		output.jumpPoints.clear()
	}

	fun NeedMoreInput(): Boolean {
		if (!partialInput.isNullOrEmpty()) return true
		return if (outputStack.size > 1) true else output.backpatches.size > 0
	}

	fun Parse(sourceCode: String, replMode: Boolean = false) {
		if (replMode) {
			val lastTok = Lexer.LastToken(sourceCode);
			// Almost any token at the end will signify line continuation, except:

			// Check for an incomplete final line by finding the last (non-comment) token.
			val isPartial = when (lastTok.type) {
				Token.Type.EOL,
				Token.Type.Identifier,
				Token.Type.Keyword,
				Token.Type.Number,
				Token.Type.RCurly,
				Token.Type.RParen,
				Token.Type.RSquare,
				Token.Type.String,
				Token.Type.Unknown -> false
				else -> true
			}
			if (isPartial) {
				partialInput += Lexer.TrimComment(sourceCode);
				return;
			}
		}
		val tokens = Lexer((partialInput ?: "") + sourceCode);
		partialInput = null;
		ParseMultipleLines(tokens);

		if (!replMode && NeedMoreInput()) {
			// Whoops, we need more input but we don't have any.  This is an error.
			tokens.lineNum++;	// (so we report PAST the last line, making it clear this is an EOF problem)
			if (outputStack.size > 1) {
				throw CompilerException(errorContext, tokens.lineNum, "'function' without matching 'end function'");
			} else if (output.backpatches.size > 0) {
				val bp = output.backpatches[output.backpatches.size - 1];
				val msg = when (bp.waitingFor) {
					"end for" -> "'for' without matching 'end for'";
					"end if" -> "'if' without matching 'end if'";
					"end while" -> "'while' without matching 'end while'";
					else -> "unmatched block opener";
				}
				throw CompilerException(errorContext, tokens.lineNum, msg);
			}
		}
	}

	/// <summary>
	/// Create a virtual machine loaded with the code we have parsed.
	/// </summary>
	/// <param name="standardOutput"></param>
	/// <returns></returns>
	fun CreateVM(standardOutput: TextOutputMethod?): TAC.Machine {
		val root = TAC.Context(output.code, null, null, null)
		return TAC.Machine(root, standardOutput)
	}

	/// <summary>
	/// Create a Function with the code we have parsed, for use as
	/// an import.  That means, it runs all that code, then at the
	/// end it returns `locals` so that the caller can get its symbols.
	/// </summary>
	/// <returns></returns>
	fun CreateImport(): Function {
		// Add one additional line to return `locals` as the function return value.
		val locals = ValVar("locals")
		output.add(TAC.Line(TAC.LTemp(0), TAC.Line.Op.ReturnA, locals))
		// Then wrap the whole thing in a Function.
		return Function(output.code)
	}

	fun REPL(line: String) {
		Parse(line)
		TAC.Dump(output.code)

		val vm = CreateVM(null)
		while (!vm.done) vm.Step()
	}

	fun AllowLineBreak(tokens:Lexer) {
		while (tokens.Peek().type == Token.Type.EOL && !tokens.AtEnd) {
			tokens.Dequeue()
		}
	}

//	delegate Value ExpressionParsingMethod(Lexer tokens, bool asLval=false, bool statementStart=false);


	/// <summary>
	/// Parse multiple statements until we run out of tokens, or reach 'end function'.
	/// </summary>
	/// <param name="tokens">Tokens.</param>
	private fun ParseMultipleLines(tokens: Lexer) {
		while (!tokens.AtEnd) {
			// Skip any blank lines
			if (tokens.Peek().type == Token.Type.EOL) {
				tokens.Dequeue();
				continue;
			}

			// Prepare a source code location for error reporting
			var location = SourceLoc(errorContext, tokens.lineNum);

			// Pop our context if we reach 'end function'.
			if (tokens.Peek().type == Token.Type.Keyword && tokens.Peek().text == "end function") {
				tokens.Dequeue();
				if (outputStack.size > 1) {
					outputStack.pop();
					output = outputStack.peek();
				} else {
					val e = CompilerException("'end function' without matching block starter");
					e.location = location;
					throw e;
				}
				continue;
			}

			// Parse one line (statement).
			val outputStart = output.code.size;
			try {
				ParseStatement(tokens);
			} catch (mse: MiniscriptException) {
				if (mse.location == null) mse.location = location;
				throw mse;
			}
			// Fill in the location info for all the TAC lines we just generated.
			for (i in outputStart until output.code.size) {
				output.code[i].location = location;
			}
		}
	}

	private fun ParseStatement(tokens: Lexer, allowExtra: Boolean = false) {
		if (tokens.Peek().type == Token.Type.Keyword && tokens.Peek().text != "not"
			&& tokens.Peek().text != "true" && tokens.Peek().text != "false") {
			// Handle statements that begin with a keyword.
			val keyword = tokens.Dequeue().text;
			when (keyword) {
				"return" -> {
					var returnValue: Value? = null;
					if (tokens.Peek().type != Token.Type.EOL) {
						returnValue = ParseExpr(tokens);
					}
					output.add(TAC.Line(TAC.LTemp(0), TAC.Line.Op.ReturnA, returnValue))
				}
				"if" -> {
					val condition = ParseExpr(tokens);
					RequireToken(tokens, Token.Type.Keyword, "then");
					// OK, now we need to emit a conditional branch, but keep track of this
					// on a stack so that when we get the corresponding "else" or  "end if",
					// we can come back and patch that jump to the right place.
					output.add(TAC.Line(null, TAC.Line.Op.GotoAifNotB, null, condition));

					// ...but if blocks also need a special marker in the backpack stack
					// so we know where to stop when patching up (possibly multiple) 'end if' jumps.
					// We'll push a special dummy backpatch here that we look for in PatchIfBlock.
					output.AddBackpatch("if:MARK");
					output.AddBackpatch("else");

					// Allow for the special one-statement if: if the next token after "then"
					// is not EOL, then parse a statement, and do the same for any else or
					// else-if blocks, until we get to EOL (and then implicitly do "end if").
					if (tokens.Peek().type != Token.Type.EOL) {
						ParseStatement(tokens, true);  // parses a single statement for the "then" body
						if (tokens.Peek().type == Token.Type.Keyword && tokens.Peek().text == "else") {
							tokens.Dequeue();	// skip "else"
							StartElseClause();
							ParseStatement(tokens, true);		// parse a single statement for the "else" body
						} else {
							RequireEitherToken(tokens, Token.Type.Keyword, "else", Token.Type.EOL);
						}
						output.PatchIfBlock();	// terminate the single-line if
					} else {
						tokens.Dequeue();	// skip EOL
					}

					return
				}
				"else" -> {
					StartElseClause();
				}
				"else if" -> {
					StartElseClause();
					val condition = ParseExpr(tokens);
					RequireToken(tokens, Token.Type.Keyword, "then");
					output.add(TAC.Line(null, TAC.Line.Op.GotoAifNotB, null, condition));
					output.AddBackpatch("else");
				}
				"end if" -> {
					// OK, this is tricky.  We might have an open "else" block or we might not.
					// And, we might have multiple open "end if" jumps (one for the if part,
					// and another for each else-if part).  Patch all that as a special case.
					output.PatchIfBlock();
				}
				"while" -> {
					// We need to note the current line, so we can jump back up to it at the end.
					output.AddJumpPoint(keyword);

					// Then parse the condition.
					val condition = ParseExpr(tokens);

					// OK, now we need to emit a conditional branch, but keep track of this
					// on a stack so that when we get the corresponding "end while",
					// we can come back and patch that jump to the right place.
					output.add(TAC.Line(null, TAC.Line.Op.GotoAifNotB, null, condition));
					output.AddBackpatch("end while");
				}
				"end while" -> {
					// Unconditional jump back to the top of the while loop.
					val jump = output.CloseJumpPoint("while");
					output.add(TAC.Line(null, TAC.Line.Op.GotoA, TAC.Num(jump.lineNum)));
					// Then, backpatch the open "while" branch to here, right after the loop.
					// And also patch any "break" branches emitted after that point.
					output.Patch(keyword, true);
				}
				"for" -> {
					// Get the loop variable, "in" keyword, and expression to loop over.
					// (Note that the expression is only evaluated once, before the loop.)
					val loopVarTok = RequireToken(tokens, Token.Type.Identifier);
					val loopVar = ValVar(loopVarTok.text!!);
					RequireToken(tokens, Token.Type.Keyword, "in");
					val stuff = ParseExpr(tokens) ?: throw CompilerException(errorContext, tokens.lineNum, "sequence expression expected for 'for' loop");

					// Create an index variable to iterate over the sequence, initialized to -1.
					val idxVar = ValVar("__" + loopVarTok.text + "_idx");
					output.add(TAC.Line(idxVar, TAC.Line.Op.AssignA, TAC.Num(-1)));

					// We need to note the current line, so we can jump back up to it at the end.
					output.AddJumpPoint(keyword);

					// Now increment the index variable, and branch to the end if it's too big.
					// (We'll have to backpatch this branch later.)
					output.add(TAC.Line(idxVar, TAC.Line.Op.APlusB, idxVar, TAC.Num(1)));
					val sizeOfSeq = ValTemp(output.nextTempNum++);
					output.add(TAC.Line(sizeOfSeq, TAC.Line.Op.LengthOfA, stuff));
					val isTooBig = ValTemp(output.nextTempNum++);
					output.add(TAC.Line(isTooBig, TAC.Line.Op.AGreatOrEqualB, idxVar, sizeOfSeq));
					output.add(TAC.Line(null, TAC.Line.Op.GotoAifB, null, isTooBig));
					output.AddBackpatch("end for");

					// Otherwise, get the sequence value into our loop variable.
					output.add(TAC.Line(loopVar, TAC.Line.Op.ElemBofIterA, stuff, idxVar));
				}
				"end for" -> {
					// Unconditional jump back to the top of the for loop.
					val jump = output.CloseJumpPoint("for");
					output.add(TAC.Line(null, TAC.Line.Op.GotoA, TAC.Num(jump.lineNum)));
					// Then, backpatch the open "for" branch to here, right after the loop.
					// And also patch any "break" branches emitted after that point.
					output.Patch(keyword, true);
				}
				"break" ->
				{
					// Emit a jump to the end, to get patched up later.
					output.add(TAC.Line(null, TAC.Line.Op.GotoA));
					output.AddBackpatch("break");
				}
				
				"continue" ->
				{
					// Jump unconditionally back to the current open jump point.
					if (output.jumpPoints.isEmpty()) {
						throw CompilerException(errorContext, tokens.lineNum, "'continue' without open loop block");
					}
					val jump = output.jumpPoints.last();
					output.add(TAC.Line(null, TAC.Line.Op.GotoA, TAC.Num(jump.lineNum)));
				}
				else -> throw CompilerException(errorContext, tokens.lineNum, "unexpected keyword '" + keyword + "' at start of line");
			}
		} else {
			ParseAssignment(tokens, allowExtra);
		}

		// A statement should consume everything to the end of the line.
		if (!allowExtra) RequireToken(tokens, Token.Type.EOL);

		// Finally, if we have a pending state, because we encountered a function(),
		// then push it onto our stack now that we're done with that statement.
		if (pendingState != null) {
//				Console.WriteLine("PUSHING NEW PARSE STATE");
			output = pendingState!!;
			outputStack.push(output);
			pendingState = null;
		}
	}


	fun StartElseClause() {
		// Back-patch the open if block, but leaving room for the jump:
		// Emit the jump from the current location, which is the end of an if-block,
		// to the end of the else block (which we'll have to back-patch later).
		output.add(TAC.Line(null, TAC.Line.Op.GotoA, null))
		// Back-patch the previously open if-block to jump here (right past the goto).
		output.Patch("else")
		// And open a new back-patch for this goto (which will jump all the way to the end if).
		output.AddBackpatch("end if")
	}

	private fun ParseAssignment(tokens: Lexer, allowExtra: Boolean = false) {
		val expr = ParseExpr(tokens, true, true);
		var lhs:Value?
		var rhs:Value?

		val peek = tokens.Peek();
		if (peek.type == Token.Type.EOL ||
			(peek.type == Token.Type.Keyword && peek.text == "else")) {
			// No explicit assignment; store an implicit result
			rhs = FullyEvaluate(expr);
			output.add(TAC.Line(null, TAC.Line.Op.AssignImplicit, rhs));
			return;
		}
		if (peek.type == Token.Type.OpAssign) {
			tokens.Dequeue();	// skip '='
			lhs = expr;
			rhs = ParseExpr(tokens);
		} else {
			// This looks like a command statement.  Parse the rest
			// of the line as arguments to a function call.
			val funcRef = expr;
			var argCount = 0;
			while (true) {
				val arg = ParseExpr(tokens);
				output.add(TAC.Line(null, TAC.Line.Op.PushParam, arg));
				argCount++;
				if (tokens.Peek().type == Token.Type.EOL) break;
				if (tokens.Peek().type == Token.Type.Keyword && tokens.Peek().text == "else") break;
				if (tokens.Peek().type == Token.Type.Comma) {
					tokens.Dequeue();
					AllowLineBreak(tokens);
					continue;
				}
				if (RequireEitherToken(tokens, Token.Type.Comma, Token.Type.EOL).type == Token.Type.EOL) break;
			}
			val result = ValTemp(output.nextTempNum++);
			output.add(TAC.Line(result, TAC.Line.Op.CallFunctionA, funcRef, TAC.Num(argCount)));
			output.add(TAC.Line(null, TAC.Line.Op.AssignImplicit, result));
			return;
		}

		// OK, now, in many cases our last TAC line at this point is an assignment to our RHS temp.
		// In that case, as a simple (but very useful) optimization, we can simply patch that to
		// assign to our lhs instead.  BUT, we must not do this if there are any jumps to the next
		// line, as may happen due to short-cut evaluation (issue #6).
		if (rhs is ValTemp && output.code.size > 0 && !output.IsJumpTarget(output.code.size)) {
			val line = output.code.last();
			if (line.lhs == rhs) {
				// Yep, that's the case.  Patch it up.
				line.lhs = lhs;
				return;
			}
		}
		// In any other case, do an assignment statement to our lhs.
		output.add(TAC.Line(lhs, TAC.Line.Op.AssignA, rhs));
	}


	private fun ParseExpr(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel: ExpressionParsingMethod = ::ParseFunction;
		return nextLevel(tokens, asLval, statementStart);
	}

	private fun ParseFunction(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel:ExpressionParsingMethod = ::ParseOr;
		val tok = tokens.Peek();
		if (tok.type != Token.Type.Keyword || tok.text != "function") return nextLevel(tokens, asLval, statementStart);
		tokens.Dequeue();

		RequireToken(tokens, Token.Type.LParen);

		val func = Function(null);

		while (tokens.Peek().type != Token.Type.RParen) {
			// parse a parameter: a comma-separated list of
			//			identifier
			//	or...	identifier = expr
			val id = tokens.Dequeue();
			if (id.type != Token.Type.Identifier) throw CompilerException(errorContext, tokens.lineNum, "got " + id + " where an identifier is required");
			var defaultValue:Value? = null;
			if (tokens.Peek().type == Token.Type.OpAssign) {
				tokens.Dequeue();	// skip '='
				defaultValue = ParseExpr(tokens);
			}
			func.parameters.add(Function.Param(id.text!!, defaultValue));
			if (tokens.Peek().type == Token.Type.RParen) break;
			RequireToken(tokens, Token.Type.Comma);
		}

		RequireToken(tokens, Token.Type.RParen);

		// Now, we need to parse the function body into its own parsing context.
		// But don't push it yet -- we're in the middle of parsing some expression
		// or statement in the current context, and need to finish that.
		if (pendingState != null) throw CompilerException(errorContext, tokens.lineNum, "can't start two functions in one statement");
		pendingState = ParseState();
		pendingState!!.nextTempNum = 1;	// (since 0 is used to hold return value)

//			Console.WriteLine("STARTED FUNCTION");

		// Create a function object attached to the new parse state code.
		func.code = pendingState!!.code;
		val valFunc = ValFunction(func);
		output.add(TAC.Line(null, TAC.Line.Op.BindContextOfA, valFunc));
		return valFunc;
	}

	private fun ParseOr(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseAnd;
		var value = nextLevel(tokens, asLval, statementStart);
		var jumpLines: MutableList<TAC.Line>? = null;
		var tok = tokens.Peek();
		while (tok.type == Token.Type.Keyword && tok.text == "or") {
			tokens.Dequeue();		// discard "or"
			value = FullyEvaluate(value);

			AllowLineBreak(tokens); // allow a line break after a binary operator

			// Set up a short-circuit jump based on the current value;
			// we'll fill in the jump destination later.  Note that the
			// usual GotoAifB opcode won't work here, without breaking
			// our calculation of intermediate truth.  We need to jump
			// only if our truth value is >= 1 (i.e. absolutely true).
			val jump = TAC.Line(null, TAC.Line.Op.GotoAifTrulyB, null, value);
			output.add(jump);
			if (jumpLines == null) jumpLines = mutableListOf();
			jumpLines.add(jump);

			val opB = nextLevel(tokens, false, false);
			val tempNum = output.nextTempNum++;
			output.add(TAC.Line(TAC.LTemp(tempNum), TAC.Line.Op.AOrB, value, opB));
			value = TAC.RTemp(tempNum);

			tok = tokens.Peek();
		}

		// Now, if we have any short-circuit jumps, those are going to need
		// to copy the short-circuit result (always 1) to our output temp.
		// And anything else needs to skip over that.  So:
		if (jumpLines != null) {
			output.add(TAC.Line(null, TAC.Line.Op.GotoA, TAC.Num(output.code.size + 2)));    // skip over this line:
			output.add(TAC.Line(value, TAC.Line.Op.AssignA, ValNumber.one));    // result = 1
			for (jump in jumpLines) {
				jump.rhsA = TAC.Num(output.code.size - 1);    // short-circuit to the above result=1 line
			}
		}

		return value;
	}

	private fun ParseAnd(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseNot;
		var value = nextLevel(tokens, asLval, statementStart);
		var jumpLines: MutableList<TAC.Line>? = null;
		var tok = tokens.Peek();
		while (tok.type == Token.Type.Keyword && tok.text == "and") {
			tokens.Dequeue();		// discard "and"
			value = FullyEvaluate(value);

			AllowLineBreak(tokens); // allow a line break after a binary operator

			// Set up a short-circuit jump based on the current value;
			// we'll fill in the jump destination later.
			val jump = TAC.Line(null, TAC.Line.Op.GotoAifNotB, null, value);
			output.add(jump);
			if (jumpLines == null) jumpLines = mutableListOf();
			jumpLines.add(jump);

			val opB = nextLevel(tokens, asLval, statementStart);
			val tempNum = output.nextTempNum++;
			output.add(TAC.Line(TAC.LTemp(tempNum), TAC.Line.Op.AAndB, value, opB));
			value = TAC.RTemp(tempNum);

			tok = tokens.Peek();
		}

		// Now, if we have any short-circuit jumps, those are going to need
		// to copy the short-circuit result (always 0) to our output temp.
		// And anything else needs to skip over that.  So:
		if (jumpLines != null) {
			output.add(TAC.Line(null, TAC.Line.Op.GotoA, TAC.Num(output.code.size + 2)));    // skip over this line:
			output.add(TAC.Line(value, TAC.Line.Op.AssignA, ValNumber.zero));    // result = 0
			for (jump in jumpLines) {
				jump.rhsA = TAC.Num(output.code.size - 1);    // short-circuit to the above result=0 line
			}
		}

		return value;
	}


	private fun ParseNot(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseIsA;
		val tok = tokens.Peek();
		var value: Value?
		if (tok.type == Token.Type.Keyword && tok.text == "not") {
			tokens.Dequeue();		// discard "not"

			AllowLineBreak(tokens); // allow a line break after a unary operator

			value = nextLevel(tokens, false, false);
			val tempNum = output.nextTempNum++;
			output.add(TAC.Line(TAC.LTemp(tempNum), TAC.Line.Op.NotA, value));
			value = TAC.RTemp(tempNum);
		} else {
			value = nextLevel(tokens, asLval, statementStart);
		}
		return value;
	}

	private fun ParseIsA(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseComparisons;
		var value = nextLevel(tokens, asLval, statementStart);
		if (tokens.Peek().type == Token.Type.Keyword && tokens.Peek().text == "isa") {
			tokens.Dequeue();		// discard the isa operator
			AllowLineBreak(tokens); // allow a line break after a binary operator
			val opB = nextLevel(tokens, false, false);
			val tempNum = output.nextTempNum++;
			output.add(TAC.Line(TAC.LTemp(tempNum), TAC.Line.Op.AisaB, value, opB));
			value = TAC.RTemp(tempNum);
		}
		return value;
	}

	private fun ParseComparisons(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseAddSub;
		var value = nextLevel(tokens, asLval, statementStart);
		var opA = value;
		var opcode = ComparisonOp(tokens.Peek().type);
		// Parse a string of comparisons, all multiplied together
		// (so every comparison must be true for the whole expression to be true).
		var firstComparison = true;
		while (opcode != TAC.Line.Op.Noop) {
			tokens.Dequeue();	// discard the operator (we have the opcode)
			opA = FullyEvaluate(opA);

			AllowLineBreak(tokens); // allow a line break after a binary operator

			val opB = nextLevel(tokens, false, false);
			var tempNum = output.nextTempNum++;
			output.add(TAC.Line(TAC.LTemp(tempNum), opcode,	opA, opB));
			if (firstComparison) {
				firstComparison = false;
			} else {
				tempNum = output.nextTempNum++;
				output.add(TAC.Line(TAC.LTemp(tempNum), TAC.Line.Op.ATimesB, value, TAC.RTemp(tempNum - 1)));
			}
			value = TAC.RTemp(tempNum);
			opA = opB;
			opcode = ComparisonOp(tokens.Peek().type);
		}
		return value;
	}

	private fun ParseAddSub(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseMultDiv;
		var value = nextLevel(tokens, asLval, statementStart);
		var tok = tokens.Peek();
		while (tok.type == Token.Type.OpPlus ||
			(tok.type == Token.Type.OpMinus
					&& (!statementStart || !tok.afterSpace  || tokens.IsAtWhitespace()))) {
			tokens.Dequeue();

			AllowLineBreak(tokens); // allow a line break after a binary operator

			value = FullyEvaluate(value);
			val opB = nextLevel(tokens, false, false);
			val tempNum = output.nextTempNum++;
			output.add(TAC.Line(
				TAC.LTemp(tempNum),
				if (tok.type == Token.Type.OpPlus) TAC.Line.Op.APlusB else TAC.Line.Op.AMinusB,
				value,
				opB)
			);
			value = TAC.RTemp(tempNum);

			tok = tokens.Peek();
		}
		return value;
	}

	private fun ParseMultDiv(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseUnaryMinus;
		var value = nextLevel(tokens, asLval, statementStart);
		var tok = tokens.Peek();
		while (tok.type == Token.Type.OpTimes || tok.type == Token.Type.OpDivide || tok.type == Token.Type.OpMod) {
			tokens.Dequeue();

			AllowLineBreak(tokens); // allow a line break after a binary operator

			value = FullyEvaluate(value);
			val opB = nextLevel(tokens, false, false);
			val tempNum = output.nextTempNum++;
			when (tok.type) {
				Token.Type.OpTimes -> output.add(TAC.Line(TAC.LTemp(tempNum), TAC.Line.Op.ATimesB, value, opB));
				Token.Type.OpDivide -> output.add(TAC.Line(TAC.LTemp(tempNum), TAC.Line.Op.ADividedByB, value, opB));
				Token.Type.OpMod -> output.add(TAC.Line(TAC.LTemp(tempNum), TAC.Line.Op.AModB, value, opB));
			}
			value = TAC.RTemp(tempNum);

			tok = tokens.Peek();
		}
		return value;
	}

	private fun ParseUnaryMinus(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseNew;
		if (tokens.Peek().type != Token.Type.OpMinus) return nextLevel(tokens, asLval, statementStart);
		tokens.Dequeue();		// skip '-'

		AllowLineBreak(tokens); // allow a line break after a unary operator

		val value = nextLevel(tokens, false, false);
		if (value is ValNumber) {
			// If what follows is a numeric literal, just invert it and be done!
			val valnum = value;
			valnum.value = -valnum.value;
			return valnum;
		}
		// Otherwise, subtract it from 0 and return a new temporary.
		val tempNum = output.nextTempNum++;
		output.add(TAC.Line(TAC.LTemp(tempNum), TAC.Line.Op.AMinusB, TAC.Num(0), value));

		return TAC.RTemp(tempNum);
	}

	private fun ParseNew(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseAddressOf;
		if (tokens.Peek().type != Token.Type.Keyword || tokens.Peek().text != "new") return nextLevel(tokens, asLval, statementStart);
		tokens.Dequeue();		// skip 'new'

		AllowLineBreak(tokens); // allow a line break after a unary operator

		// Grab a reference to our __isa value
		val isa = nextLevel(tokens, false, false);
		// Now, create a new map, and set __isa on it to that.
		// NOTE: we must be sure this map gets created at runtime, not here at parse time.
		// Since it is a mutable object, we need to return a different one each time
		// this code executes (in a loop, function, etc.).  So, we use Op.CopyA below!
		val map = ValMap();
		map.SetElem(ValString.magicIsA, isa);
		val result = ValTemp(output.nextTempNum++);
		output.add(TAC.Line(result, TAC.Line.Op.CopyA, map));
		return result;
	}

	private fun ParseAddressOf(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParsePower;
		if (tokens.Peek().type != Token.Type.AddressOf) return nextLevel(tokens, asLval, statementStart);
		tokens.Dequeue();
		AllowLineBreak(tokens); // allow a line break after a unary operator
		val value = nextLevel(tokens, true, statementStart);
		when (value) {
			is ValVar -> value.noInvoke = true
			is ValSeqElem -> value.noInvoke = true
		}
		return value;
	}

	private fun ParsePower(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseCallExpr;
		var value = nextLevel(tokens, asLval, statementStart);
		var tok = tokens.Peek();

		while (tok.type == Token.Type.OpPower) {
			tokens.Dequeue();

			AllowLineBreak(tokens); // allow a line break after a binary operator

			value = FullyEvaluate(value);
			val opB = nextLevel(tokens, false, false);
			val tempNum = output.nextTempNum++;
			output.add(TAC.Line(TAC.LTemp(tempNum), TAC.Line.Op.APowB, value, opB));
			value = TAC.RTemp(tempNum);

			tok = tokens.Peek();
		}

		return value;
	}

	private fun FullyEvaluate(value: Value?): Value? {
		when (value) {
			is ValVar -> {
				val variable = value;
				// If var was protected with @, then return it as-is; don't attempt to call it.
				if (variable.noInvoke) return value;
				// Don't invoke super; leave as-is so we can do special handling
				// of it at runtime.  Also, as an optimization, same for "self".
				if (variable.identifier == "super" || variable.identifier == "self") return value;
				// Evaluate a variable (which might be a function we need to call).
				val temp = ValTemp(output.nextTempNum++);
				output.add(TAC.Line(temp, TAC.Line.Op.CallFunctionA, value, ValNumber.zero));
				return temp;
			}
			is ValSeqElem -> {
				val elem = value
				// If sequence element was protected with @, then return it as-is; don't attempt to call it.
				if (elem.noInvoke) return value;
				// Evaluate a sequence lookup (which might be a function we need to call).
				val temp = ValTemp(output.nextTempNum++);
				output.add(TAC.Line(temp, TAC.Line.Op.CallFunctionA, value, ValNumber.zero));
				return temp;
			}
			else -> return value
		}
	}

	private fun ParseCallExpr(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseMap;
		var value = nextLevel(tokens, asLval, statementStart);
		while (true) {
			if (tokens.Peek().type == Token.Type.Dot) {
				tokens.Dequeue();	// discard '.'
				AllowLineBreak(tokens); // allow a line break after a binary operator
				val nextIdent = RequireToken(tokens, Token.Type.Identifier);
				// We're chaining sequences here; look up (by invoking)
				// the previous part of the sequence, so we can build on it.
				value = FullyEvaluate(value);
				// Now build the lookup.
				value = ValSeqElem(value!!, ValString(nextIdent.text), false);
				if (tokens.Peek().type == Token.Type.LParen && !tokens.Peek().afterSpace) {
					// If this new element is followed by parens, we need to
					// parse it as a call right away.
					value = ParseCallArgs(value, tokens);
					//val = FullyEvaluate(val);
				}
			} else if (tokens.Peek().type == Token.Type.LSquare && !tokens.Peek().afterSpace) {
				tokens.Dequeue();	// discard '['
				AllowLineBreak(tokens); // allow a line break after open bracket
				value = FullyEvaluate(value);

				if (tokens.Peek().type == Token.Type.Colon) {	// e.g., foo[:4]
					tokens.Dequeue();	// discard ':'
					AllowLineBreak(tokens); // allow a line break after colon
					val index2 = ParseExpr(tokens);
					val temp = ValTemp(output.nextTempNum++);
					Intrinsics.CompileSlice(output.code, value, null, index2, temp.tempNum);
					value = temp;
				} else {
					val index = ParseExpr(tokens);
					if (tokens.Peek().type == Token.Type.Colon) {	// e.g., foo[2:4] or foo[2:]
						tokens.Dequeue();	// discard ':'
						AllowLineBreak(tokens); // allow a line break after colon
						var index2: Value? = null;
						if (tokens.Peek().type != Token.Type.RSquare) index2 = ParseExpr(tokens);
						val temp = ValTemp(output.nextTempNum++);
						Intrinsics.CompileSlice(output.code, value, index, index2, temp.tempNum);
						value = temp;
					} else {			// e.g., foo[3]  (not a slice at all)
						if (statementStart) {
							// At the start of a statement, we don't want to compile the
							// last sequence lookup, because we might have to convert it into
							// an assignment.  But we want to compile any previous one.
							if (value is ValSeqElem) {
								val vsVal = value;
								val temp = ValTemp(output.nextTempNum++);
								output.add(TAC.Line(temp, TAC.Line.Op.ElemBofA, vsVal.sequence, vsVal.index));
								value = temp;
							}
							value = ValSeqElem(value!!, index, false);
						} else {
							// Anywhere else in an expression, we can compile the lookup right away.
							val temp = ValTemp(output.nextTempNum++);
							output.add(TAC.Line(temp, TAC.Line.Op.ElemBofA, value, index));
							value = temp;
						}
					}
				}

				RequireToken(tokens, Token.Type.RSquare);
			} else if ((value is ValVar && !value.noInvoke) || value is ValSeqElem) {
				// Got a variable... it might refer to a function!
				if (!asLval || (tokens.Peek().type == Token.Type.LParen && !tokens.Peek().afterSpace)) {
					// If followed by parens, definitely a function call, possibly with arguments!
					// If not, well, let's call it anyway unless we need an lvalue.
					value = ParseCallArgs(value, tokens);
				} else break;
			} else break;
		}

		return value;
	}

	private fun ParseMap(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseList;
		if (tokens.Peek().type != Token.Type.LCurly) return nextLevel(tokens, asLval, statementStart);
		tokens.Dequeue();
		// NOTE: we must be sure this map gets created at runtime, not here at parse time.
		// Since it is an immutable object, we need to return a different one each time
		// this code executes (in a loop, function, etc.).  So, we use Op.CopyA below!
		val map = ValMap();
		if (tokens.Peek().type == Token.Type.RCurly) {
			tokens.Dequeue();
		} else while (true) {
			AllowLineBreak(tokens); // allow a line break after a comma or open brace

			val key = ParseExpr(tokens) ?: throw CompilerException(errorContext, tokens.lineNum, "expression required as map key");
			RequireToken(tokens, Token.Type.Colon);
			AllowLineBreak(tokens); // allow a line break after a colon
			val value = ParseExpr(tokens);

			map.map[key] = value;

			if (RequireEitherToken(tokens, Token.Type.Comma, Token.Type.RCurly).type == Token.Type.RCurly) break;
		}
		val result = ValTemp(output.nextTempNum++);
		output.add(TAC.Line(result, TAC.Line.Op.CopyA, map));
		return result;
	}

	//		list	:= '[' expr [, expr, ...] ']'
	//				 | quantity
	private fun ParseList(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseQuantity;
		if (tokens.Peek().type != Token.Type.LSquare) return nextLevel(tokens, asLval, statementStart);
		tokens.Dequeue();
		// NOTE: we must be sure this list gets created at runtime, not here at parse time.
		// Since it is an immutable object, we need to return a different one each time
		// this code executes (in a loop, function, etc.).  So, we use Op.CopyA below!
		val list = ValList();
		if (tokens.Peek().type == Token.Type.RSquare) {
			tokens.Dequeue();
		} else while (true) {
			AllowLineBreak(tokens); // allow a line break after a comma or open bracket

			val elem = ParseExpr(tokens);
			list.values.add(elem);
			if (RequireEitherToken(tokens, Token.Type.Comma, Token.Type.RSquare).type == Token.Type.RSquare) break;
		}
		if (statementStart) return list;	// return the list as-is for indexed assignment (foo[3]=42)
		val result = ValTemp(output.nextTempNum++);
		output.add(TAC.Line(result, TAC.Line.Op.CopyA, list));	// use COPY on this mutable list!
		return result;
	}

	//		quantity := '(' expr ')'
	//				  | call
	private fun ParseQuantity(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val nextLevel = ::ParseAtom;
		if (tokens.Peek().type != Token.Type.LParen) return nextLevel(tokens, asLval, statementStart);
		tokens.Dequeue();
		AllowLineBreak(tokens); // allow a line break after an open paren
		val value = ParseExpr(tokens);
		RequireToken(tokens, Token.Type.RParen);
		return value;
	}

	/// <summary>
	/// Helper method that gathers arguments, emitting SetParamAasB for each one,
	/// and then emits the actual call to the given function.  It works both for
	/// a parenthesized set of arguments, and for no parens (i.e. no arguments).
	/// </summary>
	/// <returns>The call arguments.</returns>
	/// <param name="funcRef">Function to invoke.</param>
	/// <param name="tokens">Token stream.</param>
	private fun ParseCallArgs(funcRef: Value, tokens: Lexer): Value {
		var argCount = 0;
		if (tokens.Peek().type == Token.Type.LParen) {
			tokens.Dequeue();		// remove '('
			if (tokens.Peek().type == Token.Type.RParen) {
				tokens.Dequeue();
			} else while (true) {
				AllowLineBreak(tokens); // allow a line break after a comma or open paren
				val arg = ParseExpr(tokens);
				output.add(TAC.Line(null, TAC.Line.Op.PushParam, arg));
				argCount++;
				if (RequireEitherToken(tokens, Token.Type.Comma, Token.Type.RParen).type == Token.Type.RParen) break;
			}
		}
		val result = ValTemp(output.nextTempNum++);
		output.add(TAC.Line(result, TAC.Line.Op.CallFunctionA, funcRef, TAC.Num(argCount)));
		return result;
	}

	private fun ParseAtom(tokens: Lexer, asLval: Boolean = false, statementStart: Boolean = false): Value? {
		val tok = if (!tokens.AtEnd) tokens.Dequeue() else Token.EOL;
		when (tok.type) {
			Token.Type.Number -> {
				val d = tok.text!!.toDoubleOrNull() ?: throw CompilerException("invalid numeric literal: " + tok.text);
				return ValNumber(d);
			}
			Token.Type.String -> return ValString(tok.text);
			Token.Type.Identifier -> return ValVar(tok.text!!);
			Token.Type.Keyword ->
			when (tok.text) {
				"null" -> return null;
				"true" -> return ValNumber.one;
				"false" -> return ValNumber.zero;
			}
		}

		throw CompilerException("got $tok where number, string, or identifier is required");
	}


	/// <summary>
	/// The given token type and text is required. So, consume the next token,
	/// and if it doesn't match, throw an error.
	/// </summary>
	/// <param name="tokens">Token queue.</param>
	/// <param name="type">Required token type.</param>
	/// <param name="text">Required token text (if applicable).</param>
	private fun RequireToken(tokens: Lexer, type: Token.Type, text: String? = null): Token {
		val got = if (tokens.AtEnd) Token.EOL else tokens.Dequeue();
		if (got.type != type || (text != null && got.text != text)) {
			val expected = Token(type, text);
			throw CompilerException("got $got where $expected is required");
		}
		return got;
	}

	private fun RequireEitherToken(
		tokens: Lexer,
		type1: Token.Type,
		text1: String?,
		type2: Token.Type,
		text2: String? = null
	): Token {
		val got = if (tokens.AtEnd) Token.EOL else tokens.Dequeue()
		if ((got.type != type1 && got.type != type2)
			|| ((text1 != null && got.text != text1) && (text2 != null && got.text != text2))) {
			val expected1 = Token(type1, text1);
			val expected2 = Token(type2, text2);
			throw CompilerException("got $got where $expected1 or $expected2 is required");
		}
		return got;
	}

	private fun RequireEitherToken(tokens: Lexer,
	                               type1: Token.Type,
	                               type2: Token.Type,
	                               text2: String? = null): Token {
		return RequireEitherToken(tokens, type1, null, type2, text2);
	}

	companion object {
		// Find the TAC operator that corresponds to the given token type,
		// for comparisons.  If it's not a comparison operator, return TAC.Line.Op.Noop.
		private fun ComparisonOp(tokenType: Token.Type) = when (tokenType) {
			Token.Type.OpEqual -> TAC.Line.Op.AEqualB
			Token.Type.OpNotEqual -> TAC.Line.Op.ANotEqualB
			Token.Type.OpGreater -> TAC.Line.Op.AGreaterThanB
			Token.Type.OpGreatEqual -> TAC.Line.Op.AGreatOrEqualB
			Token.Type.OpLesser -> TAC.Line.Op.ALessThanB
			Token.Type.OpLessEqual -> TAC.Line.Op.ALessOrEqualB
			else -> TAC.Line.Op.Noop
		}
	}
}

typealias ExpressionParsingMethod = (tokens: Lexer, asLval: Boolean, statementStart: Boolean) -> Value?