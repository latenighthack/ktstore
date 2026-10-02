package com.latenighthack.ktstore

import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun BoundStoreKey.toAny(): Any? {
    return when (val key = this) {
        is BoundStoreKey.NullKey -> null
        is BoundStoreKey.SerializedKey -> key.value.toComparable()
        is BoundStoreKey.StringKey -> key.value
        is BoundStoreKey.BooleanKey -> key.value
        is BoundStoreKey.IntegerKey -> key.value
        is BoundStoreKey.LongKey -> key.value
        is BoundStoreKey.CompositeKey -> {
            key.values.mapIndexed { index, value ->
                Pair(key.names[index], value.toAny())
            }.toMap()
        }
    }
}

class ComparableByteArray(val rawValue: ByteArray) : Comparable<ComparableByteArray> {
    override fun compareTo(other: ComparableByteArray): Int {
        return rawValue.compareTo(other.rawValue)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ComparableByteArray) return false
        return rawValue.contentEquals(other.rawValue)
    }

    override fun hashCode(): Int {
        return rawValue.contentHashCode()
    }
}

operator fun ByteArray.compareTo(other: ByteArray): Int {
    for (i in 0 until kotlin.math.min(this.size, other.size)) {
        val cmp = this[i].toUByte().compareTo(other[i].toUByte())
        if (cmp != 0) {
            return cmp
        }
    }

    return this.size - other.size
}

fun ByteArray.toComparable(): ComparableByteArray {
    return ComparableByteArray(this)
}

public class InMemoryStoreDelegate : ScopedStoreDelegate, LifecycleStoreDelegate, IndexedQueryDelegate {
    private var closed = false
    override suspend fun close() {
        if (coroutineContext[Transaction]?.owner === this) throw StoreFailure.InvalidUsage()
        transactionMutex.withLock { closed = true }
    }
    override suspend fun deleteDatabase() { close(); activeStores.clear(); activeStoreData.clear() }
    override suspend fun <T> transaction(stores: Set<String>, mode: TransactionMode, block: suspend () -> T): T {
        if (!activeStores.keys.containsAll(stores)) throw StoreFailure.InvalidUsage()
        return transaction(block)
    }
    private val transactionMutex = Mutex()
    private class Transaction(val owner: InMemoryStoreDelegate) : AbstractCoroutineContextElement(Key) {
        var rollbackCause: Throwable? = null
        companion object Key : CoroutineContext.Key<Transaction>
    }
    override suspend fun <T> transaction(block: suspend () -> T): T {
        if (closed) throw StoreFailure.Closed()
        val current = coroutineContext[Transaction]?.takeIf { it.owner === this }
        if (current != null) {
            try { return block() } catch (error: Throwable) { current.rollbackCause = error; throw error }
        }
        return transactionMutex.withLock {
            val snapshot = activeStoreData.mapValues { (_, store) -> store.values.toMap() }
            val transaction = Transaction(this)
            try {
                val result = withContext(transaction) { block() }
                transaction.rollbackCause?.let { throw StoreFailure.Aborted(it) }
                result
            }
            catch (error: Throwable) {
                snapshot.forEach { (name, rows) -> activeStoreData.getValue(name).values.apply { clear(); putAll(rows) } }
                throw error
            }
        }
    }

    override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T = transaction(block)
    override suspend fun saveAll(tableName: String, rows: List<StoreRow>) = transaction {
        rows.forEach { save(tableName, it.data, it.keys) }
    }
    override suspend fun deleteMany(tableName: String, relations: List<StoreRelation>) = transaction {
        relations.forEach { delete(tableName, it) }
    }

    data class StoreDescriptor(
        val tableName: String,
        val keys: List<StoreKey<*>>,
        val primaryKey: StoreKey<*>?
    )

    data class ActiveStore(
        val values: MutableMap<Any, DataRow<*>>,
        val mutex: Mutex = Mutex()
    )

    data class DataRow<T>(val data: T, val values: MutableMap<Any, Any?>)

    private val registeredStores = mutableMapOf<String, StoreDescriptor>()
    private val activeStores = mutableMapOf<String, StoreDescriptor>()
    private val activeStoreData = mutableMapOf<String, ActiveStore>()

    val storeData: Map<String, ActiveStore> = activeStoreData

    override suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?) {
        registeredStores[tableName] = StoreDescriptor(tableName, keys, primaryKey)
    }

    override suspend fun createStores() {
        activeStores.putAll(registeredStores)
        activeStores.keys.forEach { activeStoreData.getOrPut(it) { ActiveStore(mutableMapOf()) } }
    }

    override suspend fun destroyStores() {
        activeStores.clear()
        activeStoreData.clear()
    }

    private suspend fun <T> modifyTable(tableName: String, callback: (MutableMap<Any, DataRow<*>>) -> T): T {
        if (closed) throw StoreFailure.Closed()
        val store = activeStoreData[tableName]!!

        return if (coroutineContext[Transaction]?.owner === this) callback(store.values)
        else transactionMutex.withLock { callback(store.values) }
    }

    override suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>) {
        val row = DataRow(data, mutableMapOf())
        val primaryKey = activeStores[tableName]!!.primaryKey!!
        var primaryKeyValue: Any? = null

        for (key in normalizeKeys(activeStores.getValue(tableName).keys, keys)) {
            val value = key.toAny()

            row.values[key.name] = value

            if (key.name == primaryKey.name) {
                primaryKeyValue = value
            }
        }

        modifyTable(tableName) {
            it.put(primaryKeyValue!!, row)
        }
    }

    override suspend fun get(tableName: String, relation: StoreRelation?): Any? {
        return getAll(tableName, relation).firstOrNull()
    }

    override suspend fun getAll(tableName: String, relation: StoreRelation?): List<Any> {
        return modifyTable(tableName) {
            it.filterValues(relation.toPredicate()).entries.sortedWith { a, b -> compareValues(a.key, b.key) }.map { it.value.data as Any }
        }
    }

    override suspend fun delete(tableName: String, relation: StoreRelation) {
        modifyTable(tableName) { values ->
            val keys = values.filterValues(relation.toPredicate()).keys

            keys.forEach {
                values.remove(it)
            }
        }
    }

    override suspend fun deleteAll(tableName: String) {
        modifyTable(tableName) {
            it.clear()
        }
    }

    private fun bound(key: StoreKey<*>, row: DataRow<*>, keys: List<StoreKey<*>>): BoundStoreKey = if (key is StoreKey.CompositeKey)
        BoundStoreKey.CompositeKey(key.name, key.names, key.names.map { name -> bound(keys.first { it.name == name }, row, keys) })
        else key.bind((row.values[key.name] as? ComparableByteArray)?.rawValue ?: row.values[key.name])

    override suspend fun query(tableName: String, query: IndexedQuery, identity: String, version: Int): QueryPage = modifyTable(tableName) { rows ->
        query.validate(identity, version, tableName)
        val declaration = activeStores.getValue(tableName)
        if (declaration.keys.none { it.name == query.index.name }) throw StoreFailure.InvalidUsage()
        validateOrderedPrimary(declaration.primaryKey!!, declaration.keys)
        val sign = if (query.direction == SortDirection.ASCENDING) 1 else -1
        val matches = rows.values.map { IndexedRow(it.data as Any, bound(query.index, it, activeStores.getValue(tableName).keys), bound(declaration.primaryKey!!, it, declaration.keys)) }
            .filter { row -> query.matches(row.index) && (query.after?.let { token ->
                val compare = compareKeys(row.index, token.indexKey).takeIf { it != 0 } ?: compareKeys(row.primary, token.primaryKey)
                compare * sign > 0
            } ?: true) }.sortedWith { a, b -> (compareKeys(a.index, b.index).takeIf { it != 0 } ?: compareKeys(a.primary, b.primary)) * sign }
        page(matches.take(query.limit + 1), query, identity, version, tableName)
    }
    override suspend fun count(tableName: String, query: IndexedQuery): Long = modifyTable(tableName) { rows -> rows.values.count { query.matches(bound(query.index, it, activeStores.getValue(tableName).keys)) }.toLong() }
    override suspend fun deleteBatch(tableName: String, query: IndexedQuery, identity: String, version: Int): Int = transaction {
        val result = query(tableName, query, identity, version)
        result.rows.forEach { delete(tableName, StoreRelation.Eq(it.primary)) }
        result.rows.size
    }

    override val isSerialized: Boolean = false

    private fun StoreRelation?.toPredicate(): ((DataRow<*>) -> Boolean) {
        val relation = this ?: return { true }

        return { row ->
            val key = relation.key

            if (key is BoundStoreKey.CompositeKey) {
                key.values.fold(true) { currentAnd, boundKey ->
                    val isEqual = if (boundKey is BoundStoreKey.SerializedKey) {
                        val value = boundKey.value

                        value.contentEquals((row.values[boundKey.name] as ComparableByteArray).rawValue)
                    } else {
                        val value = boundKey.toAny()

                        (row.values[boundKey.name] == value)
                    }

                    currentAnd && isEqual
                }
            } else if (key is BoundStoreKey.SerializedKey) {
                val value = key.value

                value.contentEquals((row.values[key.name] as ComparableByteArray).rawValue)
            } else {
                val value = key.toAny()

                (row.values[key.name] == value)
            }
        }
    }
}
