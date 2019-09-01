package com.arcnor.miniscript

import java.lang.ref.WeakReference
import java.util.*

actual class SortedMap<K, V> actual constructor(comparator: Comparator<K>): TreeMap<K, V>(comparator),  MutableMap<K, V>

actual typealias WeakReference<T> = WeakReference<T>

actual fun getTimeMillis() = System.currentTimeMillis()

actual fun String.format(vararg args: Any) = java.lang.String.format(null as Locale?, this, *args)