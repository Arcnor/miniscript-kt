package com.arcnor.miniscript

import kotlin.jvm.JvmStatic
import kotlin.math.min

object MainClass {
	private fun Test(sourceLines: List<String>, sourceLineNum:Int, expectedOutputLines: List<String>?, outputLineNum:Int) {
		val expectedOutput = expectedOutputLines ?: emptyList()

//		Console.WriteLine("TEST (LINE {0}):", sourceLineNum);
//		Console.WriteLine(string.Join("\n", sourceLines));
//		Console.WriteLine("EXPECTING (LINE {0}):", outputLineNum);
//		Console.WriteLine(string.Join("\n", expectedOutput));

		val actualOutput = mutableListOf<String>();
		val standardOutput: TextOutputMethod = { s: String -> actualOutput.add(s) }
		val miniscript = Interpreter(sourceLines, standardOutput, standardOutput);
		miniscript.implicitOutput = miniscript.standardOutput;
		miniscript.RunUntilDone(60.0, false);

//		Console.WriteLine("ACTUAL OUTPUT:");
//		Console.WriteLine(string.Join("\n", actualOutput));

		val minLen = min(expectedOutput.size, actualOutput.size)
		for (i in 0 until minLen) {
			if (actualOutput[i] != expectedOutput[i]) {
				val lineNum = outputLineNum + i;
				println("TEST FAILED AT LINE $lineNum\n  EXPECTED: ${expectedOutput[i]}\n    ACTUAL: ${actualOutput[i]}")
			}
		}
		if (expectedOutput.size > actualOutput.size) {
			println("TEST FAILED: MISSING OUTPUT AT LINE ${outputLineNum + actualOutput.size}");
			for (i in actualOutput.size until expectedOutput.size) {
				println("  MISSING: " + expectedOutput[i]);
			}
		} else if (actualOutput.size > expectedOutput.size) {
			println("TEST FAILED: EXTRA OUTPUT AT LINE ${outputLineNum + expectedOutput.size}");
			for (i in expectedOutput.size until actualOutput.size) {
				println("  EXTRA: ${actualOutput[i]}");
			}
		}
	}

	private fun runTestSuite(path: String) {
		val file = FileReader(path);

		var sourceLines: MutableList<String>? = null;
		var expectedOutput: MutableList<String>? = null;
		var testLineNum = 0;
		var outputLineNum = 0;

		var line = file.readLine();
		var lineNum = 1;
		while (line != null) {
			when {
				line.startsWith("====") -> {
					if (sourceLines != null) Test(sourceLines, testLineNum, expectedOutput, outputLineNum);
					sourceLines = null;
					expectedOutput = null;
				}
				line.startsWith("----") -> {
					expectedOutput = mutableListOf();
					outputLineNum = lineNum + 1;
				}
				expectedOutput != null -> expectedOutput.add(line)
				else -> {
					if (sourceLines == null) {
						sourceLines = mutableListOf();
						testLineNum = lineNum;
					}
					sourceLines.add(line);
				}
			}

			line = file.readLine();
			lineNum++;
		}
		if (sourceLines != null) {
			Test(sourceLines, testLineNum, expectedOutput, outputLineNum)
		}
		println("\nIntegration tests complete.\n");
	}

	private fun runFile(path: String, dumpTAC: Boolean = false) {
		val file = FileReader(path);

		val sourceLines = mutableListOf<String>();
		var line = file.readLine()
		while (line != null) {
			sourceLines.add(line)
			line = file.readLine()
		}

		val standardOutput = { s:String -> println(s) }
		val miniscript = Interpreter(sourceLines, standardOutput, standardOutput);
		miniscript.Compile();

		if (dumpTAC) {
			miniscript.vm!!.DumpTopContext();
		}

		while (!miniscript.done) {
			miniscript.RunUntilDone();
		}
	}

	const val QuickTestFilePath = "../../../QuickTest.mscp";

	// TODO: This defeats the whole purpose of having "common" code
	@JvmStatic
	fun main(args: Array<String>) {
		HostInfo.name = "Test harness";

		println("Miniscript test harness.\n");

		println("Running unit tests.\n");
		UnitTest.Run();

		println("Running test suite.\n");
		runTestSuite("TestSuite.txt");

		println("\n");

		if (File.exists(QuickTestFilePath)) {
			println("Running quick test.\n");
			runFile(QuickTestFilePath, true);
		} else {
			println("Quick test not found, skipping...\n");
		}


		if (args.size > 0) {
			runFile(args[0]);
			return;
		}

		val repl = Interpreter();
		repl.implicitOutput = repl.standardOutput;

		while (true) {
			print(if (repl.NeedMoreInput()) ">>> " else "> ");
			val inp: String? = readLine() ?: break;
			repl.REPL(inp);
		}
	}
}