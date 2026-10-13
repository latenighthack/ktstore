package com.latenighthack.ktstore

import com.latenighthack.ktstore.collection.LazyMapList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KFunction1
import kotlin.reflect.KProperty1

public sealed class StoreKey<T>(val name: String, val nullable: Boolean = false) {
    class SerializedKey(name: String, nullable: Boolean = false) : StoreKey<ByteArray>(name, nullable)
    class StringKey(name: String, nullable: Boolean = false) : StoreKey<String>(name, nullable)
    class BooleanKey(name: String, nullable: Boolean = false) : StoreKey<Boolean>(name, nullable)
    class IntegerKey(name: String, nullable: Boolean = false) : StoreKey<Int>(name, nullable)
    class LongKey(name: String, nullable: Boolean = false) : StoreKey<Long>(name, nullable)
    class CompositeKey(name: String, val names: List<String>) : StoreKey<List<BoundStoreKey>>(name)

    @Suppress("UNCHECKED_CAST")
    fun bind(any: Any?): BoundStoreKey {
        if (any == null) { require(nullable); return BoundStoreKey.NullKey(name) }
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
    class NullKey(name: String) : BoundStoreKey(name)
    class SerializedKey(name: String, val value: ByteArray) : BoundStoreKey(name)
    class StringKey(name: String, val value: String) : BoundStoreKey(name)
    class BooleanKey(name: String, val value: Boolean) : BoundStoreKey(name)
    class IntegerKey(name: String, val value: Int) : BoundStoreKey(name)
    class LongKey(name: String, val value: Long) : BoundStoreKey(name)
    class CompositeKey(name: String, val names: List<String>, val values: List<BoundStoreKey>) : BoundStoreKey(name)
}

public sealed class StoreRelation(private val binding: () -> BoundStoreKey) {
    val key: BoundStoreKey get() = binding().also {
        if (it is BoundStoreKey.NullKey || (it is BoundStoreKey.CompositeKey && it.values.any { key -> key is BoundStoreKey.NullKey }))
            throw StoreFailure.InvalidUsage("Null equality is unsupported for sparse indexes")
    }
    class Eq private constructor(binding: () -> BoundStoreKey, unused: Unit) : StoreRelation(binding) {
        constructor(key: BoundStoreKey) : this({ key }, Unit)
        internal constructor(binding: () -> BoundStoreKey) : this(binding, Unit)
    }
}

public expect fun createStoreDelegate(db: String): StoreDelegate

data class StoreRow(val data: Any, val keys: List<BoundStoreKey>)

/**
 * All stores using this delegate participate in the coroutine-scoped transaction.
 * Execute operations sequentially inside a transaction; never retain it across network calls.
 * Nested blocks share the outer transaction (they are not independent savepoints).
 */
interface TransactionalStoreDelegate : StoreDelegate {
    val supportsTransactions: Boolean get() = true
    suspend fun <T> transaction(block: suspend () -> T): T
    /** Serializes transactions sharing a logical key across server processes. */
    suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T
}

public interface StoreDelegate {
    suspend fun getMany(tableName: String, relations: List<StoreRelation>): List<Any> =
        relations.flatMap { getAll(tableName, it) }

    /** Preserves relation groups, including empty matches, without decoding payloads. */
    suspend fun getManyGrouped(tableName: String, relations: List<StoreRelation>): List<List<Any>> =
        relations.map { getAll(tableName, it) }

    suspend fun saveAll(tableName: String, rows: List<StoreRow>) {
        rows.forEach { save(tableName, it.data, it.keys) }
    }
    suspend fun deleteMany(tableName: String, relations: List<StoreRelation>) {
        relations.forEach { delete(tableName, it) }
    }

    suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?)

    suspend fun createStores()

    @Deprecated("Use explicit database lifecycle operations; schema removal requires a migration")
    suspend fun destroyStores()

    suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>)

    suspend fun get(tableName: String, relation: StoreRelation?): Any?

    suspend fun getAll(tableName: String, relation: StoreRelation?): List<Any>

    suspend fun delete(tableName: String, relation: StoreRelation)

    suspend fun deleteAll(tableName: String)

    val isSerialized: Boolean
}

public open class Store<ValueType>(
    internal val delegate: StoreDelegate,
    internal val tableName: String,
    private val writer: KFunction1<ValueType, ByteArray>,
    private val reader: KFunction1<ByteArray, ValueType>
) {
    constructor(database: Database, name: StoreName, writer: KFunction1<ValueType, ByteArray>, reader: KFunction1<ByteArray, ValueType>) :
        this(database.delegate, name.value, writer, reader) {
        this.database = database
    }

    /** Stores built from a bare delegate neither use nor invalidate a read cache. */
    private var database: Database? = null

    private var definition: StoreDefinition<ValueType>? = null
    constructor(database: Database, definition: StoreDefinition<ValueType>) :
        this(database, definition.storeName, definition::encodePayload, definition::decode) {
        val declared = database.configuration.stores.singleOrNull { it.name == definition.storeName }
        require(declared?.signature() == definition.declaration.signature()) { "Definition does not match configured store" }
        this.definition = definition
    }
    private fun registerIndex(index: Index<ValueType, *>) {
        check(definition == null) { "Definition-backed stores cannot declare local indexes" }
        indices.add(index)
    }
    internal fun encodeRow(value: ValueType): StoreRow {
        definition?.let {
            val row = it.encodeRow(value)
            return if (delegate.isSerialized) row else StoreRow(value as Any, row.keys)
        }
        return StoreRow(
            if (delegate.isSerialized) writer(value) else value as Any,
            indices.map { it.key.bind(it.accessor(value)) },
        )
    }
    internal fun decodeData(data: Any): ValueType {
        try {
            @Suppress("UNCHECKED_CAST")
            return if (delegate.isSerialized) reader(data as ByteArray) else data as ValueType
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw StoreFailure.CorruptRecord(error)
        }
    }

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

        class CompositeIndex<ValueType>(
            name: String,
            val names: List<String>,
            compositeIndices: Array<out Index<ValueType, *>>
        ) : Index<ValueType, List<BoundStoreKey>>(
            name,
            StoreKey.CompositeKey(name, names),
            {
                val value = this

                compositeIndices.map {
                    it.key.bind(it.accessor(value))
                }
            }
        ) {
            override fun eq(other: List<BoundStoreKey>) =
                StoreRelation.Eq(BoundStoreKey.CompositeKey(name, names, other))
        }
    }

    private val preparationMutex = Mutex()
    private var preparation: CompletableDeferred<Unit>? = null
    private val indices = mutableListOf<Index<ValueType, *>>()
    private var primaryKeyIndex: Index<ValueType, *>? = null

    suspend fun prepare() {
        var owner = false
        val pending = preparationMutex.withLock {
            preparation ?: CompletableDeferred<Unit>().also { preparation = it; owner = true }
        }
        if (!owner) { pending.await(); return }
        try {
            val declaration = definition?.declaration
            // Database.open() already registered definition-backed stores. Re-registering here
            // mutates the delegate's table descriptors while other stores may be reading them.
            if (declaration == null) {
                delegate.registerStore(tableName, indices.map { it.key }, primaryKeyIndex?.key)
            }
            pending.complete(Unit)
        } catch (error: Throwable) {
            pending.completeExceptionally(error)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                preparationMutex.withLock { if (preparation === pending) preparation = null }
            }
            throw error
        }
    }

    protected fun <T, R> mappedIndex(
        name: IndexName,
        property: KProperty1<ValueType, T>,
        codec: StorageCodec<T, R>,
    ): TypedIndex<ValueType, T> {
        val key = codec.key(name)
        require(key.name == name.value)
        registerIndex(CodecIndex(name.value, key) { codec.encode(property.get(this)) })
        return TypedIndex(name, key) { key.bind(codec.encode(it) as Any) }
    }

    protected fun <T : Any, R> nullableMappedIndex(
        name: IndexName, property: KProperty1<ValueType, T?>, codec: StorageCodec<T, R>,
    ): TypedIndex<ValueType, T> {
        val original = codec.key(name)
        val nullable: StoreKey<*> = when (original) {
            is StoreKey.StringKey -> StoreKey.StringKey(name.value, true)
            is StoreKey.SerializedKey -> StoreKey.SerializedKey(name.value, true)
            is StoreKey.LongKey -> StoreKey.LongKey(name.value, true)
            is StoreKey.IntegerKey -> StoreKey.IntegerKey(name.value, true)
            is StoreKey.BooleanKey -> StoreKey.BooleanKey(name.value, true)
            is StoreKey.CompositeKey -> throw StoreFailure.InvalidUsage("Declare nullable composite components separately")
        }
        @Suppress("UNCHECKED_CAST") val key = nullable as StoreKey<R?>
        registerIndex(CodecIndex(name.value, key) { property.get(this)?.let { codec.encode(it) } })
        return TypedIndex(name, key) { key.bind(codec.encode(it)) }
    }

    protected fun <T> primaryKey(index: TypedIndex<ValueType, T>) {
        check(definition == null) { "Definition-backed stores cannot declare local indexes" }
        primaryKeyIndex = indices.single { it.name == index.name.value }
    }

    protected fun primaryKey(index: Index<ValueType, *>) {
        check(definition == null) { "Definition-backed stores cannot declare local indexes" }
        primaryKeyIndex = index
    }

    // WARNING: composite keys don't serialize down, so they must not be used as a PK
    protected fun compositeIndex(vararg compositeIndices: Index<ValueType, *>): Index.CompositeIndex<ValueType> {
        val names = compositeIndices.map { it.name }
        val name = "composite_" + names.joinToString("_")

        return Index.CompositeIndex<ValueType>(name, names, compositeIndices)
            .also { registerIndex(it) }
    }

    protected fun <IndexType> serializedIndex(
        accessor: KProperty1<ValueType, IndexType?>,
        writer: KFunction1<IndexType, ByteArray>,
        overrideName: String? = null
    ) = Index.SerializedIndex<ValueType>(overrideName ?: (accessor.name + writer.name)) {
        val value = accessor(this)!!

        writer(value)
    }.also { registerIndex(it) }

    protected fun bytesIndex(
        accessor: KProperty1<ValueType, ByteArray>,
        overrideName: String? = null
    ) = Index.SerializedIndex<ValueType>(overrideName ?: (accessor.name)) {
        accessor(this)
    }.also { registerIndex(it) }

    protected fun longIndex(
        accessor: KProperty1<ValueType, Long?>,
        overrideName: String? = null
    ) = Index.LongIndex<ValueType>(overrideName ?: (accessor.name)) {
        accessor(this) ?: 0L
    }.also { registerIndex(it) }

    protected fun booleanIndex(
        accessor: KProperty1<ValueType, Boolean?>,
        overrideName: String? = null
    ) = Index.BooleanIndex<ValueType>(overrideName ?: (accessor.name)) {
        accessor(this) ?: false
    }.also { registerIndex(it) }

    protected fun stringIndex(
        accessor: KProperty1<ValueType, String?>,
        overrideName: String? = null
    ) = Index.StringIndex<ValueType>(overrideName ?: (accessor.name)) {
        accessor(this) ?: ""
    }.also { registerIndex(it) }

    @Deprecated("Use mappedIndex with an explicit persisted IndexName and a StorageCodec")
    protected fun <IndexType> longMappedIndex(
        accessor: KProperty1<ValueType, IndexType?>,
        writer: KFunction1<IndexType, Long>,
        overrideName: String? = null
    ) = Index.LongIndex<ValueType>(overrideName ?: (accessor.name + writer.name)) {
        val value = accessor(this)!!

        writer(value)
    }.also { registerIndex(it) }

    @Deprecated("Use mappedIndex with an explicit persisted IndexName and a StorageCodec")
    protected fun <IndexType> booleanMappedIndex(
        accessor: KProperty1<ValueType, IndexType?>,
        writer: KFunction1<IndexType, Boolean>,
        overrideName: String? = null
    ) = Index.BooleanIndex<ValueType>(overrideName ?: (accessor.name + writer.name)) {
        val value = accessor(this)!!

        writer(value)
    }.also { registerIndex(it) }

    @Deprecated("Use mappedIndex with an explicit persisted IndexName and a StorageCodec")
    protected fun <IndexType> stringMappedIndex(
        accessor: KProperty1<ValueType, IndexType?>,
        writer: KFunction1<IndexType, String>,
        overrideName: String? = null
    ) = Index.StringIndex<ValueType>(overrideName ?: (accessor.name + writer.name)) {
        val value = accessor(this)!!

        writer(value)
    }.also { registerIndex(it) }

    internal class ReadContext(val database: Database, val cache: SharedReadCache, val generation: Long, val capacity: Int)
    private data class UncachedRelation(val position: Int)
    private val codecIdentity: Any get() = definition ?: (writer to reader)

    private suspend fun readContext(): ReadContext? {
        val database = database ?: return null
        val cache = database.readCache ?: return null
        if (database.readCacheConfiguration.maxEntriesPerStore == 0 || currentCoroutineContext()[Database.Owner] != null) return null
        return ReadContext(database, cache, cache.generation(tableName), database.readCacheConfiguration.maxEntriesPerStore)
    }
    private fun primaryQueryKey(query: StoreRelation?): Any? {
        val primary = database?.configuration?.stores?.singleOrNull { it.name.value == tableName }?.primaryKey ?: return null
        if (query !is StoreRelation.Eq || query.key.name != primary.name) return null
        return query.key.toAny()
    }
    private fun decodeFetched(data: Any, read: ReadContext?): ValueType {
        if (read == null || read.database.readCache !== read.cache) return decodeData(data)
        @Suppress("UNCHECKED_CAST")
        return read.cache.decode(tableName, read.generation, data, codecIdentity, read.capacity,
            { decodeData(data) }, { value -> primaryKeyOf(value as ValueType).takeUnless { it === Unattributed } }) as ValueType
    }
    internal fun decodeRows(rows: List<Any>, read: ReadContext? = null): List<ValueType> {
        if (!delegate.isSerialized) { @Suppress("UNCHECKED_CAST") return rows as List<ValueType> }
        if (read == null) return LazyMapList(rows) { decodeData(it) }
        read.cache.storeRows(tableName, read.generation, rows, read.capacity)
        // Only the bounded store cache retains decoded elements, never a second per-list cache.
        return object : AbstractList<ValueType>() {
            override val size: Int get() = rows.size
            override fun get(index: Int): ValueType = decodeFetched(rows[index], read)
        }
    }

    protected suspend fun getAll(query: StoreRelation? = null): List<ValueType> {
        val read = readContext()
        val key = primaryQueryKey(query)
        if (read != null && key != null) {
            val hit = read.cache.lookup(tableName, key)
            if (hit is SharedReadCache.Lookup.Hit) return decodeRows(listOfNotNull(hit.data), read)
        }
        val rows = delegate.getAll(tableName, query)
        if (read != null && key != null && rows.size <= 1) {
            read.cache.store(tableName, read.generation, mapOf(key to rows.firstOrNull()), read.capacity)
        }
        return decodeRows(rows, read)
    }

    protected suspend fun getMany(relations: List<StoreRelation>): List<ValueType> {
        if (relations.isEmpty()) return emptyList()
        val read = readContext()
        val keys = relations.map(::primaryQueryKey)
        if (read == null) return decodeRows(delegate.getMany(tableName, relations))
        val groups = MutableList<List<Any>?>(relations.size) { null }
        val missing = mutableListOf<Int>()
        keys.forEachIndexed { index, key ->
            val hit = if (key != null) read.cache.lookup(tableName, key) else SharedReadCache.Lookup.Miss
            if (hit is SharedReadCache.Lookup.Hit) groups[index] = listOfNotNull(hit.data) else missing.add(index)
        }
        val byKey = missing.groupBy { keys[it] ?: UncachedRelation(it) }
        val fetch: suspend () -> Unit = {
            byKey.values.map { it.first() }.chunked(read.database.readCacheConfiguration.batchSize).forEach { indices ->
                val fetched = delegate.getManyGrouped(tableName, indices.map { relations[it] })
                check(fetched.size == indices.size) { "Grouped read must return one group per relation" }
                indices.forEachIndexed { offset, index ->
                    val rows = fetched[offset]
                    byKey.getValue(keys[index] ?: UncachedRelation(index)).forEach { groups[it] = rows }
                    if (keys[index] != null && rows.size <= 1) read.cache.store(tableName, read.generation, mapOf(keys[index] to rows.firstOrNull()), read.capacity)
                }
            }
        }
        // All miss batches read one backend snapshot, just like the original getMany operation.
        if (missing.isNotEmpty()) {
            val scoped = delegate as? ScopedStoreDelegate
            if (scoped?.supportsTransactions == true) scoped.transaction(setOf(tableName), TransactionMode.READ_ONLY, fetch) else fetch()
        }
        if (read.cache.generation(tableName) != read.generation) {
            // A write overlapped cache hits/miss batches. Read one fresh backend snapshot rather
            // than returning a mixture of rows from before and after that write.
            val fresh = readContext()
            return decodeRows(delegate.getMany(tableName, relations), fresh)
        }
        return decodeRows(concatenateFetchedRows(groups.map { it.orEmpty() }), read)
    }

    protected suspend fun get(query: StoreRelation? = null): ValueType? {
        val read = readContext()
        val loaded = cachedGet(query)
        @Suppress("UNCHECKED_CAST")
        if (loaded?.decoded == true) return loaded.value as ValueType?
        val data = if (loaded != null) loaded.data else delegate.get(tableName, query)
        if (data == null) {
            val key = primaryQueryKey(query)
            if (read != null && key != null) read.cache.store(tableName, read.generation, mapOf(key to null), read.capacity)
            return null
        }
        return decodeFetched(data, read)
    }

    /** Primary-key lookups outside transactions; null when the read must go to the delegate directly. */
    private suspend fun cachedGet(query: StoreRelation?): ReadBatcher.Loaded? {
        val database = database ?: return null
        val cache = database.readCache ?: return null
        val settings = database.readCacheConfiguration
        if (query !is StoreRelation.Eq || settings.maxEntriesPerStore == 0) return null
        if (currentCoroutineContext()[Database.Owner] != null) return null
        val primary = database.configuration.stores.singleOrNull { it.name.value == tableName }?.primaryKey ?: return null
        val key = query.key
        if (key.name != primary.name) return null
        val cacheKey = key.toAny()
        return when (val lookup = cache.lookup(tableName, cacheKey)) {
            is SharedReadCache.Lookup.Hit -> ReadBatcher.Loaded(lookup.data, null, false)
            SharedReadCache.Lookup.Bypass -> null
            SharedReadCache.Lookup.Miss -> cache.batcher(tableName).load(cacheKey, query, settings.batchSize) { requests ->
                loadBatch(cache, settings, requests)
            }
        }
    }

    /** Grouped results identify primary-key rows without eagerly decoding batch members. */
    private suspend fun loadBatch(cache: SharedReadCache, settings: ReadCacheConfiguration, requests: Map<Any?, ReadBatcher.Request>) {
        try {
            val generation = cache.generation(tableName)
            val fetched = delegate.getManyGrouped(tableName, requests.values.map { it.relation })
            check(fetched.size == requests.size) { "Grouped read must return one group per relation" }
            val rows = requests.keys.zip(fetched.map { it.firstOrNull() }).toMap()
            cache.store(tableName, generation, rows, settings.maxEntriesPerStore)
            requests.forEach { (key, request) ->
                request.result.complete(ReadBatcher.Loaded(rows[key], null, false))
            }
        } catch (error: Throwable) {
            requests.values.forEach { it.result.completeExceptionally(error) }
        }
    }

    private fun primaryKeyOf(value: ValueType): Any? {
        definition?.let { return it.primaryKeyOf(value).toAny() }
        val index = primaryKeyIndex ?: return Unattributed
        return index.key.bind(index.accessor(value)).toAny()
    }
    private object Unattributed

    private suspend fun <T> writing(rows: List<StoreRow>?, block: suspend () -> T): T {
        val database = database ?: return block()
        return database.writing(tableName, rows, block)
    }

    protected suspend fun delete(query: StoreRelation) {
        writing(null) { delegate.delete(tableName, query) }
    }

    protected suspend fun deleteAll() {
        writing(null) { delegate.deleteAll(tableName) }
    }

    protected suspend fun saveAll(values: List<ValueType>) {
        val rows = values.map(::encodeRow)
        writing(rows) { delegate.saveAll(tableName, rows) }
    }

    protected suspend fun deleteMany(relations: List<StoreRelation>) = writing(null) { delegate.deleteMany(tableName, relations) }

    protected suspend fun save(value: ValueType) {
        val row = encodeRow(value)
        writing(listOf(row)) { delegate.save(tableName, row.data, row.keys) }
    }
}
