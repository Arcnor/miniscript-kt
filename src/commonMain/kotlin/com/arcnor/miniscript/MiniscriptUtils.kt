package com.arcnor.miniscript

class Stopwatch {
	private var started = 0L

	fun Start() {
		started = getTimeMillis()
	}

	val ElapsedSeconds: Double
		get() = (getTimeMillis() - started) / 1000.0

	val ElapsedMillis: Long
		get() = getTimeMillis() - started
}