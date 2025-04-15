package com.latenighthack.ktstore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun BoundStoreKey.toAny(): Any? {
    return when (val key = this) {
        is BoundStoreKey.SerializedKey -> key.value.toComparable()
        is BoundStoreKey.StringKey -> key.value
        is BoundStoreKey.BooleanKey -> key.value
        is BoundStoreKey.IntegerKey -> key.value
        is BoundStoreKey.LongKey -> key.value
        is BoundStoreKey.CompositeKey -> {
            key.values.mapIndexed { index, value ->
                Pair(key.names[index], value)
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

public class InMemoryStoreDelegate : StoreDelegate {
    private data class StoreDescriptor(
        val tableName: String,
        val keys: List<StoreKey<*>>,
        val primaryKey: StoreKey<*>?
    )

    private data class ActiveStore(
        val values: MutableMap<Any, DataRow>,
        val mutex: Mutex = Mutex()
    )

    private data class DataRow(val data: Any, val values: MutableMap<Any, Any>)

    private val registeredStores = mutableMapOf<String, StoreDescriptor>()
    private val activeStores = mutableMapOf<String, StoreDescriptor>()
    private val activeStoreData = mutableMapOf<String, ActiveStore>()

    override suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?) {
        registeredStores[tableName] = StoreDescriptor(tableName, keys, primaryKey)
    }

    override suspend fun createStores() {
        activeStores.putAll(registeredStores)
        activeStoreData.putAll(activeStores.keys.map { Pair(it, ActiveStore(mutableMapOf())) })
    }

    override suspend fun destroyStores() {
        activeStores.clear()
        activeStoreData.clear()
    }

    private suspend fun <T> modifyTable(tableName: String, callback: (MutableMap<Any, DataRow>) -> T): T {
        val store = activeStoreData[tableName]!!

        return store.mutex.withLock {
            callback(store.values)
        }
    }

    override suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>) {
        val row = DataRow(data, mutableMapOf())
        val primaryKey = activeStores[tableName]!!.primaryKey!!
        var primaryKeyValue: Any? = null

        for (key in keys) {
            val value = key.toAny()

            row.values[key.name] = value!!

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
            it.filterValues(relation.toPredicate()).values.map { it.data }
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

    override val isSerialized: Boolean = false

    private fun StoreRelation?.toPredicate(): ((DataRow) -> Boolean) {
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
