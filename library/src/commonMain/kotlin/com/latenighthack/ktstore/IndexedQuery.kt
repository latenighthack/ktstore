package com.latenighthack.ktstore

/** Local database continuation, intentionally unrelated to a server pagination cursor. */
class LocalContinuation internal constructor(
    internal val identity: String, internal val version: Int, internal val store: String,
    internal val signature: List<Any?>, internal val indexKey: BoundStoreKey, internal val primaryKey: BoundStoreKey,
)
enum class SortDirection { ASCENDING, DESCENDING }
data class QueryBound(val key: BoundStoreKey, val inclusive: Boolean = true)
data class IndexedQuery(
    val index: StoreKey<*>, val limit: Int,
    val lower: QueryBound? = null, val upper: QueryBound? = null,
    val direction: SortDirection = SortDirection.ASCENDING,
    val after: LocalContinuation? = null,
) {
    init {
        require(limit > 0)
        require(lower == null || lower.key.name == index.name)
        require(upper == null || upper.key.name == index.name)
        if (index is StoreKey.LongKey || index is StoreKey.StringKey || index is StoreKey.CompositeKey)
            throw StoreFailure.InvalidUsage("Ordered queries require numeric or sortable binary indexes; migrate legacy encodings first")
    }
    internal val signature get() = listOf(index.name, lower?.key?.toAny(), lower?.inclusive, upper?.key?.toAny(), upper?.inclusive, direction)
    internal fun validate(identity: String, version: Int, store: String) {
        after?.let { if (it.identity != identity || it.version != version || it.store != store || it.signature != signature) throw StoreFailure.InvalidUsage("Continuation does not match query") }
        if (lower?.key is BoundStoreKey.NullKey || upper?.key is BoundStoreKey.NullKey) throw StoreFailure.InvalidUsage()
    }
}
class QueryPage internal constructor(val records: List<Any>, val continuation: LocalContinuation?, internal val rows: List<IndexedRow>)
internal data class IndexedRow(val data: Any, val index: BoundStoreKey, val primary: BoundStoreKey)
interface IndexedQueryDelegate : StoreDelegate {
    suspend fun query(tableName: String, query: IndexedQuery, identity: String, version: Int): QueryPage
    suspend fun count(tableName: String, query: IndexedQuery): Long
    suspend fun deleteBatch(tableName: String, query: IndexedQuery, identity: String, version: Int): Int
}
internal fun page(rows: List<IndexedRow>, query: IndexedQuery, identity: String, version: Int, table: String): QueryPage {
    val selected = rows.take(query.limit)
    val next = if (rows.size > query.limit) selected.last().let { LocalContinuation(identity, version, table, query.signature, it.index, it.primary) } else null
    return QueryPage(selected.map { it.data }, next, selected)
}
internal fun compareKeys(a: BoundStoreKey, b: BoundStoreKey): Int {
    val x = a.toAny(); val y = b.toAny()
    return compareValues(x, y)
}
internal fun compareValues(x: Any?, y: Any?): Int = when {
    x == null && y == null -> 0
    x == null -> -1
    y == null -> 1
    x is String && y is String -> x.compareTo(y)
    x is Int && y is Int -> x.compareTo(y)
    x is Long && y is Long -> x.compareTo(y)
    x is Boolean && y is Boolean -> x.compareTo(y)
    x is ComparableByteArray && y is ComparableByteArray -> x.compareTo(y)
    x is Map<*, *> && y is Map<*, *> -> {
        x.values.zip(y.values).map { (a, b) -> compareValues(a, b) }.firstOrNull { it != 0 } ?: x.size.compareTo(y.size)
    }
    else -> throw StoreFailure.InvalidUsage("Incompatible key representations")
}
internal fun IndexedQuery.matches(key: BoundStoreKey): Boolean {
    if (key is BoundStoreKey.NullKey) return false
    lower?.let { val c = compareKeys(key, it.key); if (c < 0 || (c == 0 && !it.inclusive)) return false }
    upper?.let { val c = compareKeys(key, it.key); if (c > 0 || (c == 0 && !it.inclusive)) return false }
    return true
}

internal fun validateOrderedPrimary(primary: StoreKey<*>, keys: List<StoreKey<*>>) {
    val components = if (primary is StoreKey.CompositeKey) primary.names.map { name -> keys.single { it.name == name } } else listOf(primary)
    if (components.any { it is StoreKey.StringKey || it is StoreKey.LongKey }) throw StoreFailure.InvalidUsage("Ordered queries require sortable primary keys")
}
