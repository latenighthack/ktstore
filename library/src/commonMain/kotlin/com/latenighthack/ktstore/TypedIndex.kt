package com.latenighthack.ktstore

import kotlin.reflect.KProperty1

/** Implementations define representation only; payload and domain migrations remain app-owned. */
interface StorageCodec<T, Representation> {
    fun encode(value: T): Representation
    fun key(name: IndexName): StoreKey<Representation>
}

class TypedIndex<Record, T> internal constructor(
    val name: IndexName,
    val key: StoreKey<*>,
    private val bind: (T) -> BoundStoreKey,
) {
    fun eq(value: T): StoreRelation.Eq = StoreRelation.Eq { bind(value) }
    fun query(limit: Int, lower: T? = null, upper: T? = null, lowerInclusive: Boolean = true, upperInclusive: Boolean = true,
              direction: SortDirection = SortDirection.ASCENDING, after: LocalContinuation? = null): IndexedQuery =
        IndexedQuery(key, limit, lower?.let { QueryBound(bind(it), lowerInclusive) }, upper?.let { QueryBound(bind(it), upperInclusive) }, direction, after)
}

internal class CodecIndex<Record, R>(name: String, key: StoreKey<R>, accessor: Record.() -> R) : Store.Index<Record, R>(name, key, accessor) {
    override fun eq(other: R): StoreRelation.Eq = StoreRelation.Eq(key.bind(other as Any))
}

/** Lexicographic unsigned byte representation; changing existing keys requires a migration. */
object OrderedKeyEncoding {
    fun long(value: Long): ByteArray {
        val ordered = value xor Long.MIN_VALUE
        return ByteArray(8) { i -> (ordered ushr ((7 - i) * 8)).toByte() }
    }
    fun int(value: Int): ByteArray = long(value.toLong())
    fun string(value: String): ByteArray = ByteArray(value.length * 2) { i ->
        val code = value[i / 2].code
        (if (i % 2 == 0) code ushr 8 else code).toByte()
    }
}
