package com.latenighthack.ktstore.collection

import com.latenighthack.ktstore.SpinLock

/**
 * A list over a stable source snapshot. Elements are decoded only on access and successful
 * decodes are cached, including null values. Standard list operations come from [AbstractList].
 */
class LazyMapList<T, U>(
    private val list: List<T>,
    private val transform: ((T) -> U)
) : AbstractList<U>() {
    private val lock = SpinLock()
    private val decoded = mutableMapOf<Int, Lazy<U>>()
    override val size: Int get() = list.size

    override fun get(index: Int): U {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index: $index, size: $size")
        // Only entry creation holds the lock. Decoding uses Lazy's synchronization outside it.
        val entry = lock.withLock { decoded.getOrPut(index) { lazy { transform(list[index]) } } }
        return entry.value
    }
}
