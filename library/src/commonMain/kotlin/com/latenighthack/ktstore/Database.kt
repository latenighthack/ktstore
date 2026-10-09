package com.latenighthack.ktstore

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmInline
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

@JvmInline value class StoreName(val value: String) { init { require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) } }
@JvmInline value class IndexName(val value: String) { init { require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) } }
enum class TransactionMode { READ_ONLY, READ_WRITE }

data class StoreDeclaration(val name: StoreName, val keys: List<StoreKey<*>>, val primaryKey: StoreKey<*>) {
    init {
        require(keys.map { it.name }.distinct().size == keys.size)
        require(keys.all { it.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && it.name !in setOf("__value", "_value", "_rowid") })
        require(keys.any { it.name == primaryKey.name })
        require(!primaryKey.nullable)
        if (primaryKey is StoreKey.CompositeKey) require(primaryKey.names.all { name -> keys.any { it.name == name && !it.nullable } })
        keys.filterIsInstance<StoreKey.CompositeKey>().forEach { composite ->
            require(composite.names.isNotEmpty() && composite.names.distinct().size == composite.names.size)
            require(composite.names.all { component -> keys.any { it.name == component && it !is StoreKey.CompositeKey } })
        }
    }
}

data class DatabaseConfiguration(
    val identity: String,
    val version: Int,
    val stores: List<StoreDeclaration>,
    val migrations: List<DatabaseMigration> = emptyList(),
    val legacyVersion: Int? = null,
    val operationTimeoutMillis: Long = 30_000,
    val busyTimeoutMillis: Int = 5_000,
    /** Optional tables managed by another owner; ktstore never creates or migrates them. */
    val externalTables: Set<String> = emptySet(),
) {
    init {
        require(identity.isNotBlank() && version > 0)
        require(operationTimeoutMillis > 0 && busyTimeoutMillis >= 0)
        require(stores.map { it.name }.distinct().size == stores.size)
        require(stores.none { it.name.value.startsWith("ktstore_") })
        require(externalTables.all { it.matches(Regex("[a-z_][a-z0-9_]{0,62}")) &&
            !it.startsWith("ktstore_") && !it.startsWith("sqlite_") && it != "android_metadata" })
        val ownedNames = (stores + migrations.flatMap { it.sourceSchema.orEmpty() + it.targetSchema.orEmpty() })
            .map { it.name.value.lowercase().take(63) }.toSet()
        require(externalTables.none { it in ownedNames }) { "External and owned tables must not overlap" }
        require(migrations.map { it.fromVersion }.distinct().size == migrations.size)
        require(migrations.all { it.fromVersion >= 1 && it.toVersion == it.fromVersion + 1 && it.toVersion <= version })
    }
    internal fun steps(oldVersion: Int): List<DatabaseMigration> = (oldVersion until version).map { from ->
        migrations.singleOrNull { it.fromVersion == from } ?: throw StoreFailure.Migration()
    }
}

class DatabaseMigration private constructor(
    val fromVersion: Int, val toVersion: Int,
    internal val sourceSchema: List<StoreDeclaration>?, internal val targetSchema: List<StoreDeclaration>?,
    val migrate: suspend MigrationScope.() -> Unit,
) {
    constructor(fromVersion: Int, toVersion: Int, migrate: suspend MigrationScope.() -> Unit) : this(fromVersion, toVersion, null, null, migrate)
    companion object {
        fun configured(fromVersion: Int, toVersion: Int, source: List<StoreDeclaration>, target: List<StoreDeclaration>, migrate: suspend MigrationScope.() -> Unit): DatabaseMigration =
            DatabaseMigration(fromVersion, toVersion, source.map { it.snapshot() }, target.map { it.snapshot() }, migrate)
    }
}

/** Migration callbacks contain database awaits and bounded synchronous transformations only. */
interface MigrationOperations {
    suspend fun storeExists(name: StoreName): Boolean = throw StoreFailure.InvalidUsage("Migration store discovery is unsupported")
    suspend fun validateStore(declaration: StoreDeclaration): Unit = throw StoreFailure.InvalidUsage("Typed migrations require schema validation")
    suspend fun createStore(declaration: StoreDeclaration)
    suspend fun removeStore(name: StoreName)
    suspend fun addIndex(store: StoreName, key: StoreKey<*>)
    suspend fun removeIndex(store: StoreName, name: IndexName)
    suspend fun transform(store: StoreName, transform: (ByteArray) -> StoreRow)
    suspend fun rebuildStore(store: StoreName, declaration: StoreDeclaration, transform: (ByteArray) -> StoreRow)
}

@RestrictsSuspension
class MigrationScope internal constructor(private val operations: MigrationOperations, private val context: CoroutineContext) {
    private var active = true
    internal var failure: Throwable? = null
    internal fun finish() { active = false }
    private suspend fun <T> call(block: suspend () -> T): T = suspendCoroutineUninterceptedOrReturn { continuation ->
        if (!active) throw StoreFailure.InvalidUsage("Migration scope has expired")
        block.startCoroutine(object : Continuation<T> {
            override val context = this@MigrationScope.context
            override fun resumeWith(result: Result<T>) { result.exceptionOrNull()?.let { failure = it }; continuation.resumeWith(result) }
        })
        COROUTINE_SUSPENDED
    }
    suspend fun <A, B> transformMappedStore(source: StoreDefinition<A>, target: StoreDefinition<B>, mapping: (A) -> B) = call {
        require(source.declaration.signature() == target.declaration.signature() && source.indexEncodings == target.indexEncodings)
        operations.validateStore(source.declaration)
        operations.transform(source.storeName, mappedRows(source, target, mapping))
        operations.validateStore(target.declaration)
    }
    suspend fun <A, B> rebuildMappedStore(source: StoreDefinition<A>, target: StoreDefinition<B>, mapping: (A) -> B) = call {
        operations.validateStore(source.declaration)
        operations.rebuildStore(source.storeName, target.declaration, mappedRows(source, target, mapping))
        operations.validateStore(target.declaration)
    }
    internal suspend fun <A, B> applyMapping(entry: MappedStoreMigration<A, B>) {
        if (entry.operation == "transform") transformMappedStore(entry.source, entry.target, entry.mapping)
        else rebuildMappedStore(entry.source, entry.target, entry.mapping)
    }
    suspend fun storeExists(name: StoreName): Boolean = call { operations.storeExists(name) }
    suspend fun createStore(declaration: StoreDeclaration) = call { operations.createStore(declaration) }
    suspend fun removeStore(name: StoreName) = call { operations.removeStore(name) }
    suspend fun addIndex(store: StoreName, key: StoreKey<*>) = call { operations.addIndex(store, key) }
    suspend fun removeIndex(store: StoreName, name: IndexName) = call { operations.removeIndex(store, name) }
    suspend fun rebuildIndex(store: StoreName, key: StoreKey<*>) { removeIndex(store, IndexName(key.name)); addIndex(store, key) }
    suspend fun transform(store: StoreName, transform: (ByteArray) -> StoreRow) = call { operations.transform(store, transform) }
    suspend fun rebuildStore(store: StoreName, declaration: StoreDeclaration, transform: (ByteArray) -> StoreRow) = call { operations.rebuildStore(store, declaration, transform) }
}
internal suspend fun runMigration(operations: MigrationOperations, block: suspend MigrationScope.() -> Unit) {
    val scope = MigrationScope(operations, coroutineContext)
    try {
        suspendCoroutine<Unit> { continuation ->
            block.startCoroutine(scope, object : Continuation<Unit> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) = continuation.resumeWith(result)
            })
        }
        scope.failure?.let { throw StoreFailure.Migration(it) }
    } finally { scope.finish() }
}

interface LifecycleStoreDelegate : StoreDelegate {
    suspend fun close()
    suspend fun deleteDatabase()
}
interface ScopedStoreDelegate : TransactionalStoreDelegate {
    suspend fun <T> transaction(stores: Set<String>, mode: TransactionMode, block: suspend () -> T): T
}

/** Restricted scopes cannot await network calls, launch children, or switch dispatchers. */
@RestrictsSuspension
class TransactionScope internal constructor(
    private val delegate: StoreDelegate,
    private val stores: Set<String>,
    private val mode: TransactionMode,
    private val context: CoroutineContext,
    private val identity: String,
    private val version: Int,
) {
    private var active = true
    private var executing = false
    internal var failure: Throwable? = null
    internal fun finish() { active = false; context[Database.Owner]?.active = false }

    private suspend fun <T> operation(store: StoreName, write: Boolean = false, block: suspend () -> T): T =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            if (!active || executing || store.value !in stores || (write && mode == TransactionMode.READ_ONLY)) {
                val error = StoreFailure.InvalidUsage("Invalid transaction scope access")
                failure = error
                throw error
            }
            executing = true
            block.startCoroutine(object : Continuation<T> {
                override val context = this@TransactionScope.context
                override fun resumeWith(result: Result<T>) {
                    executing = false
                    result.exceptionOrNull()?.let { failure = it }
                    continuation.resumeWith(result)
                }
            })
            COROUTINE_SUSPENDED
        }

    suspend fun get(store: StoreName, relation: StoreRelation? = null): Any? = operation(store) { delegate.get(store.value, relation) }
    suspend fun getAll(store: StoreName, relation: StoreRelation? = null): List<Any> = operation(store) { delegate.getAll(store.value, relation) }
    suspend fun save(store: StoreName, row: StoreRow) = operation(store, true) { delegate.save(store.value, row.data, row.keys) }
    suspend fun saveAll(store: StoreName, rows: List<StoreRow>) = operation(store, true) { delegate.saveAll(store.value, rows) }
    suspend fun delete(store: StoreName, relation: StoreRelation) = operation(store, true) { delegate.delete(store.value, relation) }
    suspend fun clear(store: StoreName) = operation(store, true) { delegate.deleteAll(store.value) }

    private fun checkStore(store: Store<*>) {
        if (store.delegate !== delegate) {
            val error = StoreFailure.InvalidUsage("Store belongs to another database handle")
            failure = error
            throw error
        }
    }
    suspend fun <T> get(store: Store<T>, relation: StoreRelation? = null): T? = operation(StoreName(store.tableName)) {
        checkStore(store)
        delegate.get(store.tableName, relation)?.let { store.decodeData(it) }
    }
    suspend fun <T> getAll(store: Store<T>, relation: StoreRelation? = null): List<T> = operation(StoreName(store.tableName)) {
        checkStore(store)
        delegate.getAll(store.tableName, relation).map { store.decodeData(it) }
    }
    suspend fun <T> save(store: Store<T>, value: T) = operation(StoreName(store.tableName), true) {
        checkStore(store); val row = store.encodeRow(value); delegate.save(store.tableName, row.data, row.keys)
    }
    suspend fun <T> saveAll(store: Store<T>, values: List<T>) = operation(StoreName(store.tableName), true) {
        checkStore(store); delegate.saveAll(store.tableName, values.map { store.encodeRow(it) })
    }
    suspend fun delete(store: Store<*>, relation: StoreRelation) = operation(StoreName(store.tableName), true) {
        checkStore(store); delegate.delete(store.tableName, relation)
    }

    suspend fun query(store: StoreName, query: IndexedQuery): QueryPage = operation(store) {
        query.validate(identity, version, store.value)
        (delegate as? IndexedQueryDelegate ?: throw StoreFailure.InvalidUsage()).query(store.value, query, identity, version)
    }
    suspend fun count(store: StoreName, query: IndexedQuery): Long = operation(store) {
        if (query.after != null) throw StoreFailure.InvalidUsage("Counts do not accept continuation")
        query.validate(identity, version, store.value)
        (delegate as? IndexedQueryDelegate ?: throw StoreFailure.InvalidUsage()).count(store.value, query)
    }
    suspend fun deleteBatch(store: StoreName, query: IndexedQuery): Int = operation(store, true) {
        query.validate(identity, version, store.value)
        (delegate as? IndexedQueryDelegate ?: throw StoreFailure.InvalidUsage()).deleteBatch(store.value, query, identity, version)
    }

    suspend fun <T> transaction(
        stores: Set<StoreName>, mode: TransactionMode = this.mode,
        block: suspend TransactionScope.() -> T,
    ): T = suspendCoroutineUninterceptedOrReturn { continuation ->
        if (!active || executing || !this.stores.containsAll(stores.map { it.value }) ||
            (this.mode == TransactionMode.READ_ONLY && mode == TransactionMode.READ_WRITE)) {
            val error = StoreFailure.InvalidUsage("Invalid nested transaction scope")
            failure = error
            throw error
        }
        executing = true
        val names = stores.map { it.value }.toSet()
        val owner = context[Database.Owner]?.narrow(names, mode)
        val nestedContext = if (owner == null) context else context + owner
        val nested = TransactionScope(delegate, names, mode, nestedContext, identity, version)
        block.startCoroutine(nested, object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                nested.finish()
                executing = false
                val error = nested.failure ?: owner?.state?.failure ?: result.exceptionOrNull()
                if (error != null) failure = error
                continuation.resumeWith(if (error != null) Result.failure(error) else result)
            }
        })
        COROUTINE_SUSPENDED
    }
}

/** New handles own one delegate; closing never reactivates an old handle. */
class Database(configuration: DatabaseConfiguration, internal val delegate: LifecycleStoreDelegate) {
    val configuration = configuration.snapshot()
    private val mutex = Mutex()
    private var opened = false
    private var closed = false
    private var closing: CompletableDeferred<Unit>? = null
    private val jobs = mutableSetOf<Job>()
    internal class AccessState {
        val gate = Mutex()
        var failure: Throwable? = null
    }
    internal class Owner(
        val database: Database, val stores: Set<String>, val mode: TransactionMode,
        val scoped: Boolean, val state: AccessState = AccessState(),
    ) : AbstractCoroutineContextElement(Key) {
        var active = true
        fun narrow(stores: Set<String>, mode: TransactionMode) = Owner(database, stores, mode, true, state)
        companion object Key : CoroutineContext.Key<Owner>
    }
    companion object {
        private val registryMutex = Mutex()
        private val handles = mutableMapOf<String, MutableSet<Database>>()
        private val deletions = mutableMapOf<String, CompletableDeferred<Unit>>()
    }
    suspend fun open() {
        mutex.withLock { if (closed) throw StoreFailure.Closed() }
        while (true) {
            val deletion = registryMutex.withLock {
                val pending = deletions[configuration.identity]
                if (pending == null) handles.getOrPut(configuration.identity) { mutableSetOf() }.add(this)
                pending
            }
            if (deletion == null) break
            deletion.await()
        }
        try { mutex.withLock {
            if (closed) throw StoreFailure.Closed()
            if (!opened) {
                configuration.stores.forEach { delegate.registerStore(it.name.value, it.keys, it.primaryKey) }
                delegate.createStores()
                opened = true
            }
        } } catch (error: Throwable) {
            // A failed partial open remains owned for close; a concurrently closed handle does not.
            if (mutex.withLock { closed }) releaseRegistration()
            throw error
        }
    }
    private suspend fun releaseRegistration() = registryMutex.withLock {
        handles[configuration.identity]?.let { owned ->
            owned.remove(this)
            if (owned.isEmpty()) handles.remove(configuration.identity)
        }
    }
    suspend fun <T> transaction(
        stores: Set<StoreName>, mode: TransactionMode = TransactionMode.READ_WRITE,
        block: suspend TransactionScope.() -> T,
    ): T = coroutineScope {
        if (coroutineContext[Owner] != null) throw StoreFailure.InvalidUsage("Use the transaction scope for nested operations")
        val job = coroutineContext[Job]!!
        val names = stores.map { it.value }.toSet()
        mutex.withLock {
            if (closed) throw StoreFailure.Closed()
            if (!opened) throw StoreFailure.InvalidUsage("Database must be opened first")
            if (names.isEmpty() || !configuration.stores.map { it.name.value }.containsAll(names)) throw StoreFailure.InvalidUsage()
            jobs.add(job)
        }
        try {
            val backend = delegate as? ScopedStoreDelegate ?: throw StoreFailure.InvalidUsage("Scoped transactions are unsupported")
            val owner = Owner(this@Database, names, mode, true)
            withContext(owner) {
                backend.transaction(names, mode) {
                    val scope = TransactionScope(delegate, names, mode, currentCoroutineContext(), configuration.identity, configuration.version)
                    try {
                        val value = suspendCoroutine<T> { continuation ->
                            block.startCoroutine(scope, object : Continuation<T> {
                                override val context: CoroutineContext = EmptyCoroutineContext
                                override fun resumeWith(result: Result<T>) = continuation.resumeWith(result)
                            })
                        }
                        (scope.failure ?: owner.state.failure)?.let { throw StoreFailure.Aborted(it) }
                        value
                    } finally { scope.finish() }
                }
            }
        } finally { withContext(NonCancellable) { mutex.withLock { jobs.remove(job) } } }
    }
    /**
     * Coroutine-scoped atomic writes for repositories that already own typed stores.
     * The callback must execute sequential local database work; never await network I/O.
     * The logical lock coordinates PostgreSQL processes, not just this handle.
     */
    suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T {
        require(lockKey.isNotBlank())
        val backend = delegate as? TransactionalStoreDelegate ?: throw StoreFailure.InvalidUsage()
        val existing = coroutineContext[Owner]
        if (existing?.database === this) {
            if (!existing.active || existing.scoped) {
                val error = StoreFailure.InvalidUsage("Logical transactions cannot escape a restricted scope")
                existing.state.failure = error
                throw error
            }
            return backend.transaction(lockKey, block)
        }
        if (existing != null) throw StoreFailure.InvalidUsage("Cannot nest another database transaction")
        val job = coroutineContext[Job] ?: throw StoreFailure.InvalidUsage()
        mutex.withLock {
            if (closed) throw StoreFailure.Closed()
            if (!opened) throw StoreFailure.InvalidUsage("Database must be opened first")
            jobs.add(job)
        }
        val owner = Owner(this, configuration.stores.map { it.name.value }.toSet(), TransactionMode.READ_WRITE, false)
        try { return withContext(owner) {
            backend.transaction(lockKey) {
                val value = block()
                owner.state.failure?.let { throw StoreFailure.Aborted(it) }
                value
            }
        } }
        finally { owner.active = false; withContext(NonCancellable) { mutex.withLock { jobs.remove(job) } } }
    }

    /**
     * Bounded indexed work joins this handle's current atomic transaction, or opens a restricted
     * transaction when called outside one. Calls must be sequential local database work.
     * A caught access/query failure aborts the owning transaction; parallel helper calls are invalid.
     */
    suspend fun query(store: StoreName, query: IndexedQuery): QueryPage {
        val owner = currentCoroutineContext()[Owner]
        if (owner == null) return transaction(setOf(store), TransactionMode.READ_ONLY) { validateIndex(store, query); query(store, query) }
        return indexed(owner, store, query, false) { it.query(store.value, query, configuration.identity, configuration.version) }
    }
    suspend fun count(store: StoreName, query: IndexedQuery): Long {
        val owner = currentCoroutineContext()[Owner]
        if (owner == null) return transaction(setOf(store), TransactionMode.READ_ONLY) { validateIndex(store, query); count(store, query) }
        return indexed(owner, store, query, false) {
            if (query.after != null) throw StoreFailure.InvalidUsage("Counts do not accept continuation")
            it.count(store.value, query)
        }
    }
    suspend fun deleteBatch(store: StoreName, query: IndexedQuery): Int {
        val owner = currentCoroutineContext()[Owner]
        if (owner == null) return transaction(setOf(store)) { validateIndex(store, query); deleteBatch(store, query) }
        return indexed(owner, store, query, true) { it.deleteBatch(store.value, query, configuration.identity, configuration.version) }
    }
    private fun validateIndex(store: StoreName, query: IndexedQuery) {
        query.validate(configuration.identity, configuration.version, store.value)
        val declaration = configuration.stores.singleOrNull { it.name == store } ?: throw StoreFailure.InvalidUsage()
        if (declaration.keys.none { it.name == query.index.name && it::class == query.index::class })
            throw StoreFailure.InvalidUsage("Index is not declared by this store")
    }
    private suspend fun <T> indexed(
        owner: Owner, store: StoreName, query: IndexedQuery, write: Boolean,
        block: suspend (IndexedQueryDelegate) -> T,
    ): T {
        var locked = false
        try {
            if (owner.database !== this || !owner.active || store.value !in owner.stores ||
                (write && owner.mode == TransactionMode.READ_ONLY)) throw StoreFailure.InvalidUsage("Invalid indexed transaction access")
            locked = owner.state.gate.tryLock()
            if (!locked) throw StoreFailure.InvalidUsage("Parallel indexed operations are unsupported")
            validateIndex(store, query)
            currentCoroutineContext().ensureActive()
            return block(delegate as? IndexedQueryDelegate ?: throw StoreFailure.InvalidUsage("Indexed queries are unsupported"))
        } catch (error: Throwable) {
            owner.state.failure = error
            throw error
        } finally { if (locked) owner.state.gate.unlock() }
    }


    suspend fun clearStores(stores: Set<StoreName>) = transaction(stores) { stores.forEach { clear(it) } }
    suspend fun close() {
        if (coroutineContext[Owner]?.database === this) throw StoreFailure.InvalidUsage("Cannot close inside a transaction")
        var owner = false
        val completion = mutex.withLock {
            closed = true
            closing ?: CompletableDeferred<Unit>().also { closing = it; owner = true }
        }
        if (!owner) { completion.await(); return }
        withContext(NonCancellable) {
            try {
                val pending = mutex.withLock { jobs.toList() }
                pending.forEach { it.cancel(CancellationException("Database connection closed")) }
                pending.joinAll()
                delegate.close()
                releaseRegistration()
                completion.complete(Unit)
            } catch (error: Throwable) { completion.completeExceptionally(error); throw error }
        }
    }
    suspend fun deleteDatabase() {
        if (coroutineContext[Owner] != null) throw StoreFailure.InvalidUsage("Cannot delete inside a transaction")
        var owner = false
        val completion = registryMutex.withLock {
            deletions[configuration.identity] ?: CompletableDeferred<Unit>().also { deletions[configuration.identity] = it; owner = true }
        }
        if (!owner) { completion.await(); return }
        try {
            val existing = registryMutex.withLock { handles[configuration.identity]?.toList().orEmpty() }
            existing.forEach { it.close() }
            close()
            delegate.deleteDatabase()
            completion.complete(Unit)
        } catch (error: Throwable) { completion.completeExceptionally(error); throw error }
        finally { withContext(NonCancellable) { registryMutex.withLock { deletions.remove(configuration.identity) } } }
    }
}

/** Native callers provide an absolute app-owned SQLite file location. */
expect fun createDatabase(configuration: DatabaseConfiguration, location: String? = null): Database

internal fun DatabaseConfiguration.fingerprint(): String = stores.sortedBy { it.name.value }.joinToString(";") { store ->
    store.name.value + ":" + store.primaryKey.name + ":" + store.keys.sortedBy { it.name }.joinToString(",") { key ->
        key.name + ":" + key.nullable + ":" + when (key) {
            is StoreKey.SerializedKey -> "bytes"
            is StoreKey.StringKey -> "string"
            is StoreKey.IntegerKey -> "int"
            is StoreKey.LongKey -> "legacyLong"
            is StoreKey.BooleanKey -> "boolean"
            is StoreKey.CompositeKey -> "composite(" + key.names.joinToString("+") + ")"
        }
    }
}

internal fun DatabaseConfiguration.snapshot(): DatabaseConfiguration = copy(
    stores = stores.map { declaration ->
        val keys = declaration.keys.map { key ->
            if (key is StoreKey.CompositeKey) StoreKey.CompositeKey(key.name, key.names.toList()) else key
        }
        StoreDeclaration(declaration.name, keys, keys.single { it.name == declaration.primaryKey.name })
    },
    migrations = migrations.toList(),
    externalTables = externalTables.toSet(),
)
