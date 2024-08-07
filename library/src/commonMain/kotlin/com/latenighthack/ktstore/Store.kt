package com.latenighthack.ktstore

import com.latenighthack.ktstore.collection.LazyMapList
import kotlin.reflect.KFunction1
import kotlin.reflect.KProperty1

public sealed class StoreKey<T>(val name: String) {
    class SerializedKey(name: String) : StoreKey<ByteArray>(name)
    class StringKey(name: String) : StoreKey<String>(name)
    class BooleanKey(name: String) : StoreKey<Boolean>(name)
    class IntegerKey(name: String) : StoreKey<Int>(name)
    class LongKey(name: String) : StoreKey<Long>(name)
    class CompositeKey(name: String, val names: List<String>) : StoreKey<List<BoundStoreKey>>(name)

    @Suppress("UNCHECKED_CAST")
    fun bind(any: Any): BoundStoreKey {
        return when (this) {
            is SerializedKey -> BoundStoreKey.SerializedKey(name, any as ByteArray)
            is StringKey -> BoundStoreKey.StringKey(name, any as String)
            is BooleanKey -> BoundStoreKey.BooleanKey(name, any as Boolean)
            is IntegerKey -> BoundStoreKey.IntegerKey(name, any as Int)
            is LongKey -> BoundStoreKey.LongKey(name, any as Long)
            is CompositeKey -> BoundStoreKey.CompositeKey(name, names, any as List<BoundStoreKey>)
        }
    }
}

public sealed class BoundStoreKey(val name: String) {
    class SerializedKey(name: String, val value: ByteArray) : BoundStoreKey(name)
    class StringKey(name: String, val value: String) : BoundStoreKey(name)
    class BooleanKey(name: String, val value: Boolean) : BoundStoreKey(name)
    class IntegerKey(name: String, val value: Int) : BoundStoreKey(name)
    class LongKey(name: String, val value: Long) : BoundStoreKey(name)
    class CompositeKey(name: String, val names: List<String>, val values: List<BoundStoreKey>) : BoundStoreKey(name)
}

public sealed class StoreRelation(val key: BoundStoreKey) {
    class Eq(key: BoundStoreKey) : StoreRelation(key)
}

public expect fun createStoreDelegate(db: String): StoreDelegate

public interface StoreDelegate {
    suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?)

    suspend fun createStores()

    suspend fun destroyStores()

    suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>)

    suspend fun get(tableName: String, relation: StoreRelation?): Any?

    suspend fun getAll(tableName: String, relation: StoreRelation?): List<Any>

    suspend fun delete(tableName: String, relation: StoreRelation)

    suspend fun deleteAll(tableName: String)

    val isSerialized: Boolean
}

public open class Store<ValueType>(
    private val delegate: StoreDelegate,
    private val tableName: String,
    private val writer: KFunction1<ValueType, ByteArray>,
    private val reader: KFunction1<ByteArray, ValueType>
) {
    sealed class Query<ValueType, IndexType> {
        inner class Eq<ValueType, IndexType>(
            val index: Index<ValueType, IndexType>,
            val value: IndexType
        ) : Query<ValueType, IndexType>()
    }

    @Suppress("UNCHECKED_CAST")
    sealed class Index<ValueType, IndexType>(
        val name: String,
        val key: StoreKey<IndexType>,
        val accessor: ValueType.() -> IndexType
    ) {
        abstract fun eq(other: IndexType): StoreRelation.Eq

        class SerializedIndex<ValueType>(name: String, accessor: ValueType.() -> ByteArray) :
            Index<ValueType, ByteArray>(
                name,
                StoreKey.SerializedKey(name),
                accessor
            ) {
            override fun eq(other: ByteArray) = StoreRelation.Eq(BoundStoreKey.SerializedKey(name, other))
        }

        class LongIndex<ValueType>(name: String, accessor: ValueType.() -> Long) : Index<ValueType, Long>(
            name,
            StoreKey.LongKey(name),
            accessor
        ) {
            override fun eq(other: Long) = StoreRelation.Eq(BoundStoreKey.LongKey(name, other))
        }

        class IntegerIndex<ValueType>(name: String, accessor: ValueType.() -> Int) : Index<ValueType, Int>(
            name,
            StoreKey.IntegerKey(name),
            accessor
        ) {
            override fun eq(other: Int) = StoreRelation.Eq(BoundStoreKey.IntegerKey(name, other))
        }

        class BooleanIndex<ValueType>(name: String, accessor: ValueType.() -> Boolean) : Index<ValueType, Boolean>(
            name,
            StoreKey.BooleanKey(name),
            accessor
        ) {
            override fun eq(other: Boolean) = StoreRelation.Eq(BoundStoreKey.BooleanKey(name, other))
        }

        class StringIndex<ValueType>(name: String, accessor: ValueType.() -> String) : Index<ValueType, String>(
            name,
            StoreKey.StringKey(name),
            accessor
        ) {
            override fun eq(other: String) = StoreRelation.Eq(BoundStoreKey.StringKey(name, other))
        }

        class CompositeIndex<ValueType>(name: String, val names: List<String>) : Index<ValueType, List<BoundStoreKey>>(
            name,
            StoreKey.CompositeKey(name, names),
            { listOf() }
        ) {
            override fun eq(other: List<BoundStoreKey>) =
                StoreRelation.Eq(BoundStoreKey.CompositeKey(name, names, other))
        }
    }

    private var isPrepared = AtomicBoolean(false)
    private val indices = mutableListOf<Index<ValueType, *>>()
    private var primaryKeyIndex: Index<ValueType, *>? = null

    suspend fun prepare() {
        if (isPrepared.compareAndSwap(expected = false, new = true)) {
            delegate.registerStore(tableName, indices.map { it.key }, primaryKeyIndex?.let { it.key })
        }
    }

    protected fun primaryKey(index: Index<ValueType, *>) {
        primaryKeyIndex = index
    }

    // WARNING: composite keys don't serialize down, so they must not be used as a PK
    protected fun compositeIndex(vararg compositeIndices: Index<ValueType, *>): Index.CompositeIndex<ValueType> {
        val names = compositeIndices.map { it.name }
        val name = "composite_" + names.joinToString("_")

        return Index.CompositeIndex<ValueType>(name, names)
            .also { indices.add(it) }
    }

    protected fun <IndexType> serializedIndex(
        accessor: KProperty1<ValueType, IndexType?>,
        writer: KFunction1<IndexType, ByteArray>
    ) = Index.SerializedIndex<ValueType>(accessor.name) {
        val value = accessor(this)!!

        writer(value)
    }.also { indices.add(it) }

    protected fun <IndexType> bytesIndex(
        accessor: KProperty1<ValueType, ByteArray>,
    ) = Index.SerializedIndex<ValueType>(accessor.name) {
        accessor(this)
    }.also { indices.add(it) }

    protected fun <IndexType> longIndex(
        accessor: KProperty1<ValueType, IndexType?>,
        writer: KFunction1<IndexType, Long>
    ) = Index.LongIndex<ValueType>(accessor.name) {
        val value = accessor(this)!!
        writer(value)
    }.also { indices.add(it) }

    protected suspend fun getAll(query: StoreRelation? = null): List<ValueType> {
        val dataList = delegate.getAll(tableName, query)

        return if (delegate.isSerialized) {
            LazyMapList(dataList) {
                reader(it as ByteArray)
            }
        } else {
            @Suppress("UNCHECKED_CAST")
            dataList as List<ValueType>
        }
    }

    protected suspend fun get(query: StoreRelation? = null): ValueType? {
        val data = delegate.get(tableName, query)

        return if (delegate.isSerialized) {
            (data as? ByteArray)?.let {
                reader(it)
            }
        } else {
            @Suppress("UNCHECKED_CAST")
            data as? ValueType
        }
    }

    protected suspend fun delete(query: StoreRelation) {
        delegate.delete(tableName, query)
    }

    protected suspend fun deleteAll() {
        delegate.deleteAll(tableName)
    }

    protected suspend fun save(value: ValueType) {
        val data = if (delegate.isSerialized) {
            writer(value)
        } else {
            value as Any
        }

        val keys = indices.map {
            it.key.bind(it.accessor(value)!!)
        }

        delegate.save(tableName, data, keys)
    }
}
