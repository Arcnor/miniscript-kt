package com.arcnor.miniscript

actual class SortedMap<K, V> actual constructor(comparator: Comparator<K>) : MutableMap<K, V> {
	override val size: Int
		get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.

	override fun containsKey(key: K): Boolean {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	override fun containsValue(value: V): Boolean {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	override fun get(key: K): V? {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	override fun isEmpty(): Boolean {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
		get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
	override val keys: MutableSet<K>
		get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
	override val values: MutableCollection<V>
		get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.

	override fun clear() {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	override fun put(key: K, value: V): V? {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	override fun putAll(from: Map<out K, V>) {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	override fun remove(key: K): V? {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}
}

actual typealias WeakReference<T> = kotlin.native.ref.WeakReference<T>

// TODO: Typealias??
actual fun getTimeMillis() = kotlin.system.getTimeMillis()

actual class FileReader actual constructor(path: String) {
	actual fun readLine(): String? {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}

	actual fun close() {
	}
}

actual fun readLine() = kotlin.io.readLine()

actual object File {
	actual fun exists(path: String): Boolean {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}
}

actual fun String.format(vararg args: Any): String {
	TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
}