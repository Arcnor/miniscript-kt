package com.arcnor.miniscript

import java.io.BufferedReader
import java.io.Console
import java.io.FileReader
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.Comparator

actual class SortedMap<K, V> actual constructor(comparator: Comparator<K>): TreeMap<K, V>(comparator),  MutableMap<K, V>

actual typealias WeakReference<T> = WeakReference<T>

actual fun getTimeMillis() = System.currentTimeMillis()
actual class FileReader actual constructor(path: String) {
	private var reader = BufferedReader(FileReader(path))

	actual fun readLine(): String? = reader.readLine()
	actual fun close() = reader.close()
}

actual fun readLine() = System.console().readLine()

actual object File {
	actual fun exists(path: String): Boolean = Files.exists(Path.of(path))
}

actual fun String.format(vararg args: Any) = java.lang.String.format(null as Locale?, this, *args)