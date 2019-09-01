package com.arcnor.miniscript

inline fun <K, V> mutableSortedMapOf(comparator: Comparator<K>) = SortedMap<K, V>(comparator)

expect class SortedMap<K, V>(comparator: Comparator<K>): MutableMap<K, V> {
}

expect class WeakReference<T: Any>(referred: T) {
	fun get(): T?
}

expect fun getTimeMillis(): Long

expect fun String.format(vararg args: Any): String

fun <E> List<E>.peek() = this[size - 1]
fun <E> MutableList<E>.push(value: E) = add(value)
fun <E> MutableList<E>.pop() = removeAt(size - 1)