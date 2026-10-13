package com.latenighthack.ktstore

/** Counts reads that reach the backend while forwarding scoped transactions. */
internal class CountingDelegate(private val backend: LifecycleStoreDelegate) : ScopedStoreDelegate, LifecycleStoreDelegate {
    private val scoped get() = backend as ScopedStoreDelegate
    var gets = 0
    var allReads = 0
    val groupedBatches = mutableListOf<Int>()
    val batches = mutableListOf<Int>()

    override suspend fun get(tableName: String, relation: StoreRelation?): Any? { gets++; return backend.get(tableName, relation) }
    override suspend fun getMany(tableName: String, relations: List<StoreRelation>): List<Any> {
        batches.add(relations.size)
        return backend.getMany(tableName, relations)
    }
    override suspend fun getAll(tableName: String, relation: StoreRelation?): List<Any> { allReads++; return backend.getAll(tableName, relation) }
    override suspend fun getManyGrouped(tableName: String, relations: List<StoreRelation>): List<List<Any>> {
        groupedBatches.add(relations.size)
        batches.add(relations.size)
        return backend.getManyGrouped(tableName, relations)
    }
    override suspend fun saveAll(tableName: String, rows: List<StoreRow>) = backend.saveAll(tableName, rows)
    override suspend fun deleteMany(tableName: String, relations: List<StoreRelation>) = backend.deleteMany(tableName, relations)
    override suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?) = backend.registerStore(tableName, keys, primaryKey)
    override suspend fun createStores() = backend.createStores()
    @Deprecated("Use explicit database lifecycle operations; schema removal requires a migration")
    @Suppress("DEPRECATION")
    override suspend fun destroyStores() = backend.destroyStores()
    override suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>) = backend.save(tableName, data, keys)
    override suspend fun delete(tableName: String, relation: StoreRelation) = backend.delete(tableName, relation)
    override suspend fun deleteAll(tableName: String) = backend.deleteAll(tableName)
    override val isSerialized get() = backend.isSerialized
    override val supportsTransactions get() = scoped.supportsTransactions
    override suspend fun <T> transaction(block: suspend () -> T): T = scoped.transaction(block)
    override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T = scoped.transaction(lockKey, block)
    override suspend fun <T> transaction(stores: Set<String>, mode: TransactionMode, block: suspend () -> T): T = scoped.transaction(stores, mode, block)
    override suspend fun close() = backend.close()
    override suspend fun deleteDatabase() = backend.deleteDatabase()
}
