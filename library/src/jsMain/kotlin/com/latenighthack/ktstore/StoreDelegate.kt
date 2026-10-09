package com.latenighthack.ktstore

import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.js.Promise
import kotlin.js.json

suspend fun <T> Promise<T>.await(): T = suspendCancellableCoroutine { cont ->
    then({ if (cont.isActive) cont.resume(it) }, { if (cont.isActive) cont.resumeWithException(it) })
}

actual fun createStoreDelegate(db: String): StoreDelegate = IndexDB(db)

/** Injectable without importing DOM globals, including when loaded in a service worker. */
class IndexedDbBackend(
    val factory: dynamic = js("globalThis.indexedDB"),
    val keyRange: dynamic = js("globalThis.IDBKeyRange"),
)

class IndexDB(
    private val databaseName: String,
    private val backend: IndexedDbBackend = IndexedDbBackend(),
    configuration: DatabaseConfiguration? = null,
) : ScopedStoreDelegate, LifecycleStoreDelegate, IndexedQueryDelegate {
    private val configuration = configuration?.snapshot()
    constructor(configuration: DatabaseConfiguration, backend: IndexedDbBackend = IndexedDbBackend()) :
        this(configuration.identity, backend, configuration)

    companion object { private val deletions = mutableMapOf<String, CompletableDeferred<Unit>>() }

    private data class Declaration(val name: String, val keys: List<StoreKey<*>>, val primary: StoreKey<*>?)
    private val declarations = linkedMapOf<String, Declaration>()
    private var connection: dynamic = null
    private var pendingOpen: Deferred<Unit>? = null
    private var closed = false
    private val active = mutableSetOf<Tx>()
    private val timeout get() = configuration?.operationTimeoutMillis ?: 30_000L
    override val isSerialized = true
    private class Completed<T>(val value: T)
    private suspend fun <T> bounded(block: suspend CoroutineScope.() -> T): T {
        val result = withTimeoutOrNull(timeout) { Completed(block()) } ?: throw StoreFailure.Timeout()
        return result.value
    }

    private class Tx(val owner: IndexDB, val raw: dynamic, val stores: Set<String>, val mode: TransactionMode) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Tx>
        val completion = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        var blockFinished = false
        fun abort(error: Throwable) {
            failure = failure ?: error
            try { raw.abort() } catch (_: Throwable) { completion.completeExceptionally(failure!!) }
        }
    }

    private fun failure(error: dynamic): Throwable {
        val cause = if (error is Throwable) error else null
        return when (error?.name as? String) {
            "QuotaExceededError" -> StoreFailure.Quota(cause)
            "ConstraintError", "DataError" -> StoreFailure.Constraint(cause)
            "AbortError" -> StoreFailure.Aborted(cause)
            "VersionError" -> StoreFailure.Migration(cause)
            "InvalidStateError", "TransactionInactiveError" -> StoreFailure.Closed()
            else -> StoreFailure.Unavailable(cause)
        }
    }

    override suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?) {
        if (closed) throw StoreFailure.Closed()
        val existing = declarations[tableName]
        if (existing != null && (existing.keys.map { it.name } != keys.map { it.name } || existing.primary?.name != primaryKey?.name))
            throw StoreFailure.InvalidUsage("Conflicting store declarations")
        declarations[tableName] = Declaration(tableName, keys.toList(), primaryKey)
    }

    private fun indexName(key: StoreKey<*>) = if (key is StoreKey.CompositeKey) "idx_${key.names.joinToString("_")}" else "idx_${key.name}"
    private fun indexName(key: BoundStoreKey) = if (key is BoundStoreKey.CompositeKey) "idx_${key.names.joinToString("_")}" else "idx_${key.name}"
    private fun createIndex(store: dynamic, key: StoreKey<*>) {
        val path: dynamic = if (key is StoreKey.CompositeKey) key.names.toTypedArray() else key.name
        store.createIndex(indexName(key), path, json("unique" to false))
    }
    private fun createStore(db: dynamic, declaration: Declaration): dynamic {
        val primary = declaration.primary
        // Keep the legacy compound property keyPath: old rows already store the array there.
        val options = if (primary == null) json("keyPath" to "_rowid", "autoIncrement" to true) else json("keyPath" to primary.name)
        val store = db.createObjectStore(declaration.name, options)
        declaration.keys.forEach { createIndex(store, it) }
        return store
    }

    override suspend fun createStores() {
        if (closed) throw StoreFailure.Closed()
        deletions[databaseName]?.let { bounded { it.await() } }
        if (connection != null) return
        pendingOpen?.let { it.await(); return }
        if (backend.factory == null || backend.keyRange == null) throw StoreFailure.Unavailable()
        val completion = CompletableDeferred<Unit>()
        var abandoned = false
        var upgradeFailure: Throwable? = null
        val request = try { backend.factory.open(databaseName, configuration?.version ?: 1) } catch (error: Throwable) { throw failure(error) }
        pendingOpen = completion
        request.onerror = { _: dynamic -> completion.completeExceptionally(upgradeFailure ?: failure(request.error)); Unit }
        request.onblocked = { _: dynamic -> abandoned = true; completion.completeExceptionally(StoreFailure.Blocked("open")); Unit }
        request.onupgradeneeded = { event: dynamic ->
            if (abandoned || closed) request.transaction.abort()
            else {
                val db = request.result
                val raw = request.transaction
                val upgrade: suspend () -> Unit = {
                    try {
                        val old = (event.oldVersion as Number).toInt()
                        if (old == 0) declarations.values.forEach { createStore(db, it) }
                        else {
                            val config = configuration ?: throw StoreFailure.Migration()
                            val scope = UpgradeScope(db, raw)
                            val steps = config.steps(old)
                            steps.firstOrNull()?.sourceSchema?.let { schema ->
                                if (db.objectStoreNames.contains("ktstore_schema") as Boolean) {
                                    val expected = DatabaseConfiguration("schema", 1, schema).fingerprint()
                                    if (this@IndexDB.request(raw.objectStore("ktstore_schema").get("declaration")) != expected) throw StoreFailure.Migration()
                                }
                            }
                            steps.forEach { step ->
                                step.sourceSchema?.let { validateUpgrade(db, raw, it) }
                                runMigration(scope, step.migrate)
                                step.targetSchema?.let { validateUpgrade(db, raw, it) }
                            }
                        }
                        if (configuration != null) {
                            val metadata = if (db.objectStoreNames.contains("ktstore_schema") as Boolean) raw.objectStore("ktstore_schema") else db.createObjectStore("ktstore_schema")
                            this@IndexDB.request(metadata.put(configuration.fingerprint(), "declaration"))
                        }
                        declarations.values.forEach { declaration ->
                            if (!(db.objectStoreNames.contains(declaration.name) as Boolean)) throw StoreFailure.Migration()
                            val store = raw.objectStore(declaration.name)
                            declaration.keys.forEach { if (!(store.indexNames.contains(indexName(it)) as Boolean)) throw StoreFailure.Migration() }
                        }
                    } catch (error: Throwable) { upgradeFailure = StoreFailure.Migration(error); raw.abort() }
                }
                upgrade.startCoroutine(object : Continuation<Unit> {
                    override val context: CoroutineContext = Dispatchers.Unconfined
                    override fun resumeWith(result: Result<Unit>) {
                        result.exceptionOrNull()?.let { upgradeFailure = StoreFailure.Migration(it); raw.abort() }
                    }
                })
            }
        }
        request.onsuccess = { _: dynamic ->
            val db = request.result
            if (abandoned || closed) db.close()
            else {
                val validate: suspend () -> Unit = {
                    try {
                        if (configuration != null) {
                            if (!(db.objectStoreNames.contains("ktstore_schema") as Boolean)) throw StoreFailure.Migration()
                            val tx = db.transaction((declarations.keys + "ktstore_schema").toTypedArray(), "readonly")
                            val validationComplete = CompletableDeferred<Unit>()
                            tx.oncomplete = { _: dynamic -> validationComplete.complete(Unit); Unit }
                            tx.onabort = { _: dynamic -> validationComplete.completeExceptionally(failure(tx.error)); Unit }
                            tx.onerror = { _: dynamic -> validationComplete.completeExceptionally(failure(tx.error)); Unit }
                            declarations.values.forEach { declaration ->
                                val store = tx.objectStore(declaration.name)
                                if (store.keyPath != declaration.primary?.name) throw StoreFailure.Migration()
                                declaration.keys.forEach { key ->
                                    val actual = store.index(indexName(key)).keyPath
                                    val expected: dynamic = if (key is StoreKey.CompositeKey) key.names.toTypedArray() else key.name
                                    if (js("JSON.stringify(actual)") != js("JSON.stringify(expected)")) throw StoreFailure.Migration()
                                }
                            }
                            if (this@IndexDB.request(tx.objectStore("ktstore_schema").get("declaration")) != configuration.fingerprint()) throw StoreFailure.Migration()
                            // An open handle must not expose a still-active validation transaction.
                            // Empty schemas can close/delete immediately after open.
                            validationComplete.await()
                        }
                        if (abandoned || closed) { db.close(); completion.completeExceptionally(StoreFailure.Closed()) }
                        else {
                            connection = db
                            db.onversionchange = { _: dynamic -> shutdown(); Unit }
                            db.onclose = { _: dynamic -> shutdown(); Unit }
                            completion.complete(Unit)
                        }
                    } catch (error: Throwable) { db.close(); completion.completeExceptionally(error) }
                }
                validate.startCoroutine(object : Continuation<Unit> {
                    override val context: CoroutineContext = Dispatchers.Unconfined
                    override fun resumeWith(result: Result<Unit>) { result.exceptionOrNull()?.let { db.close(); completion.completeExceptionally(it) } }
                })
            }
        }
        try { bounded { completion.await() } }
        catch (error: Throwable) { abandoned = true; throw error }
        finally { if (pendingOpen === completion) pendingOpen = null }
    }

    private fun shutdown() {
        closed = true
        active.toList().forEach { it.abort(StoreFailure.Closed()) }
        pendingOpen?.let { (it as? CompletableDeferred<Unit>)?.completeExceptionally(StoreFailure.Closed()) }
        connection?.close()
        connection = null
    }
    override suspend fun close() {
        if (coroutineContext[Tx]?.owner === this) throw StoreFailure.InvalidUsage("Cannot close inside a transaction")
        shutdown()
    }
    override suspend fun deleteDatabase() {
        close()
        deletions[databaseName]?.let { bounded { it.await() }; return }
        val terminal = CompletableDeferred<Unit>()
        deletions[databaseName] = terminal
        try {
            bounded {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val request = backend.factory.deleteDatabase(databaseName)
                    request.onsuccess = { _: dynamic ->
                        deletions.remove(databaseName); terminal.complete(Unit)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    request.onerror = { _: dynamic ->
                        val error = failure(request.error)
                        deletions.remove(databaseName); terminal.completeExceptionally(error)
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                    request.onblocked = { _: dynamic -> if (continuation.isActive) continuation.resumeWithException(StoreFailure.Blocked("delete")) }
                }
            }
        } catch (error: Throwable) { throw error }
    }
    @Deprecated("Use clearStores, close, or deleteDatabase explicitly")
    override suspend fun destroyStores(): Unit = throw StoreFailure.InvalidUsage("Removing stores requires a schema migration")

    override suspend fun <T> transaction(block: suspend () -> T): T =
        throw StoreFailure.InvalidUsage("IndexedDB transactions require participating stores")
    override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T =
        throw StoreFailure.InvalidUsage("IndexedDB does not support advisory locks")

    override suspend fun <T> transaction(stores: Set<String>, mode: TransactionMode, block: suspend () -> T): T {
        val current = coroutineContext[Tx]
        if (current != null) {
            if (current.owner !== this || !current.stores.containsAll(stores) || (current.mode == TransactionMode.READ_ONLY && mode == TransactionMode.READ_WRITE)) {
                val error = StoreFailure.InvalidUsage("Invalid nested transaction")
                current.abort(error)
                throw error
            }
            try { return block() } catch (error: Throwable) { current.abort(error); throw error }
        }
        if (closed) throw StoreFailure.Closed()
        if (connection == null) throw StoreFailure.InvalidUsage("Database must be opened first")
        if (stores.isEmpty() || !declarations.keys.containsAll(stores)) throw StoreFailure.InvalidUsage()
        return withContext(Dispatchers.Unconfined) {
            val raw = connection.transaction(stores.toTypedArray(), if (mode == TransactionMode.READ_ONLY) "readonly" else "readwrite")
            val tx = Tx(this@IndexDB, raw, stores, mode)
            active.add(tx)
            raw.oncomplete = { _: dynamic ->
                if (!tx.blockFinished) tx.completion.completeExceptionally(StoreFailure.InvalidUsage("Transaction suspended outside database operations"))
                else if (tx.failure != null) tx.completion.completeExceptionally(tx.failure!!)
                else tx.completion.complete(Unit)
                Unit
            }
            raw.onabort = { _: dynamic -> tx.completion.completeExceptionally(tx.failure ?: StoreFailure.Aborted(failure(raw.error))); Unit }
            raw.onerror = { _: dynamic -> tx.failure = tx.failure ?: failure(raw.error); Unit }
            try {
                bounded {
                    val value = withContext(tx) { block() }
                    tx.blockFinished = true
                    tx.failure?.let { throw StoreFailure.Aborted(it) }
                    tx.completion.await()
                    value
                }
            } catch (error: Throwable) { tx.abort(error); throw error }
            finally { active.remove(tx) }
        }
    }

    private suspend fun request(request: dynamic): Any? = suspendCancellableCoroutine { continuation ->
        request.onsuccess = { _: dynamic -> if (continuation.isActive) continuation.resume(request.result) }
        request.onerror = { _: dynamic -> if (continuation.isActive) continuation.resumeWithException(failure(request.error)) }
    }
    private fun value(key: BoundStoreKey): dynamic = when (key) {
        is BoundStoreKey.NullKey -> js("undefined")
        is BoundStoreKey.StringKey -> key.value
        is BoundStoreKey.IntegerKey -> key.value
        is BoundStoreKey.LongKey -> key.value.toString()
        is BoundStoreKey.BooleanKey -> if (key.value) 1 else 0
        is BoundStoreKey.SerializedKey -> key.value
        is BoundStoreKey.CompositeKey -> key.values.map { value(it) }.toTypedArray()
    }
    private fun row(data: Any, keys: List<BoundStoreKey>): dynamic = json("_value" to data).also { result ->
        keys.forEach { result[it.name] = value(it) }
    }
    private suspend fun <T> access(table: String, write: Boolean, block: suspend (dynamic) -> T): T {
        val tx = coroutineContext[Tx]
        if (tx != null) {
            if (closed || tx.owner !== this || table !in tx.stores || (write && tx.mode == TransactionMode.READ_ONLY)) {
                val error = StoreFailure.InvalidUsage("Invalid transaction access")
                tx.abort(error); throw error
            }
            try { return block(tx.raw.objectStore(table)) } catch (error: Throwable) { tx.abort(error); throw error }
        }
        return transaction(setOf(table), if (write) TransactionMode.READ_WRITE else TransactionMode.READ_ONLY) {
            access(table, write, block)
        }
    }
    override suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>) { access(tableName, true) { request(it.put(row(data, normalizeKeys(declarations.getValue(tableName).keys, keys)))); Unit } }
    override suspend fun saveAll(tableName: String, rows: List<StoreRow>) {
        if (rows.isEmpty()) return
        access(tableName, true) { store -> rows.forEach { request(store.put(row(it.data, normalizeKeys(declarations.getValue(tableName).keys, it.keys)))) } }
    }
    override suspend fun get(tableName: String, relation: StoreRelation?): Any? = getAll(tableName, relation).firstOrNull()
    override suspend fun getAll(tableName: String, relation: StoreRelation?): List<Any> = access(tableName, false) { store ->
        val raw = if (relation == null) request(store.getAll()) else request(store.index(indexName(relation.key)).getAll(backend.keyRange.only(value(relation.key))))
        val declaration = declarations.getValue(tableName)
        val primary = declaration.primary
        val records = (raw as Array<dynamic>).toList()
        val ordered = if (primary == null) records else records.sortedWith { a, b -> compareKeys(bound(primary, a[primary.name], declaration), bound(primary, b[primary.name], declaration)) }
        ordered.map { it._value as Any }
    }
    override suspend fun deleteAll(tableName: String) { access(tableName, true) { request(it.clear()); Unit } }
    override suspend fun delete(tableName: String, relation: StoreRelation) {
        access(tableName, true) { store ->
            val keys = request(store.index(indexName(relation.key)).getAllKeys(backend.keyRange.only(value(relation.key)))) as Array<dynamic>
            keys.forEach { request(store.delete(it)) }
        }
    }
    override suspend fun deleteMany(tableName: String, relations: List<StoreRelation>) {
        if (relations.isEmpty()) return
        access(tableName, true) { relations.forEach { delete(tableName, it) } }
    }

    private fun range(query: IndexedQuery): dynamic {
        val lower = query.lower; val upper = query.upper
        return when {
            lower != null && upper != null -> backend.keyRange.bound(value(lower.key), value(upper.key), !lower.inclusive, !upper.inclusive)
            lower != null -> backend.keyRange.lowerBound(value(lower.key), !lower.inclusive)
            upper != null -> backend.keyRange.upperBound(value(upper.key), !upper.inclusive)
            else -> null
        }
    }
    private fun bound(key: StoreKey<*>, raw: dynamic, declaration: Declaration): BoundStoreKey = when (key) {
        is StoreKey.CompositeKey -> BoundStoreKey.CompositeKey(key.name, key.names, key.names.mapIndexed { i, name -> bound(declaration.keys.single { it.name == name }, raw[i], declaration) })
        is StoreKey.SerializedKey -> {
            val array: dynamic = js("new Uint8Array(raw.buffer || raw, raw.byteOffset || 0, raw.byteLength)")
            key.bind(ByteArray(array.length as Int) { (array[it] as Int).toByte() })
        }
        is StoreKey.IntegerKey -> key.bind((raw as Number).toInt())
        is StoreKey.StringKey -> key.bind(raw as String)
        is StoreKey.LongKey -> key.bind((raw as String).toLong())
        is StoreKey.BooleanKey -> key.bind((raw as Number).toInt() != 0)
        else -> throw StoreFailure.InvalidUsage("Ordered queries require sortable primary keys")
    }
    override suspend fun query(tableName: String, query: IndexedQuery, identity: String, version: Int): QueryPage = access(tableName, false) { store ->
        query.validate(identity, version, tableName)
        val declaration = declarations.getValue(tableName)
        val primary = declaration.primary ?: throw StoreFailure.InvalidUsage()
        validateOrderedPrimary(primary, declaration.keys)
        val index = store.index(indexName(query.index))
        val direction = if (query.direction == SortDirection.ASCENDING) "next" else "prev"
        val rows = mutableListOf<IndexedRow>()
        suspendCancellableCoroutine<Unit> { continuation ->
            val effective = query.after?.let { token ->
                if (query.direction == SortDirection.ASCENDING) query.copy(lower = QueryBound(token.indexKey))
                else query.copy(upper = QueryBound(token.indexKey))
            } ?: query
            val request = index.openCursor(range(effective), direction)
            request.onerror = { _: dynamic -> if (continuation.isActive) continuation.resumeWithException(failure(request.error)) }
            request.onsuccess = { _: dynamic ->
                if (continuation.isActive) {
                    try {
                        val cursor = request.result
                        if (cursor == null || rows.size > query.limit) continuation.resume(Unit)
                        else {
                            val item = IndexedRow(cursor.value._value as Any, bound(query.index, cursor.key, declaration), bound(primary, cursor.primaryKey, declaration))
                            val after = query.after
                            val comparison = if (after == null) 1 else (compareKeys(item.index, after.indexKey).takeIf { it != 0 } ?: compareKeys(item.primary, after.primaryKey)) * (if (query.direction == SortDirection.ASCENDING) 1 else -1)
                            if (comparison > 0) rows.add(item)
                            if (comparison < 0 && after != null) cursor.continuePrimaryKey(value(after.indexKey), value(after.primaryKey)) else cursor.`continue`()
                        }
                    } catch (error: Throwable) { continuation.resumeWithException(error) }
                }
            }
        }
        page(rows, query, identity, version, tableName)
    }
    override suspend fun count(tableName: String, query: IndexedQuery): Long = access(tableName, false) { store ->
        (request(store.index(indexName(query.index)).count(range(query))) as Number).toLong()
    }
    override suspend fun deleteBatch(tableName: String, query: IndexedQuery, identity: String, version: Int): Int = access(tableName, true) { store ->
        val result = query(tableName, query, identity, version)
        result.rows.forEach { request(store.delete(value(it.primary))) }
        result.rows.size
    }

    private fun validateUpgrade(db: dynamic, tx: dynamic, schema: List<StoreDeclaration>) {
        val names = (0 until (db.objectStoreNames.length as Int)).map { db.objectStoreNames.item(it) as String }.filter { it != "ktstore_schema" }.toSet()
        if (names != schema.map { it.name.value }.toSet()) throw StoreFailure.Migration()
        schema.forEach { validateDeclaration(tx, it) }
    }
    private fun validateDeclaration(tx: dynamic, declaration: StoreDeclaration) {
        val store = tx.objectStore(declaration.name.value)
        if (store.keyPath != declaration.primaryKey.name) throw StoreFailure.Migration()
        val indices = (0 until (store.indexNames.length as Int)).map { store.indexNames.item(it) as String }.toSet()
        if (indices != declaration.keys.map { indexName(it) }.toSet()) throw StoreFailure.Migration()
        declaration.keys.forEach { key ->
            val index = store.index(indexName(key))
            val actual = index.keyPath
            val expected: dynamic = if (key is StoreKey.CompositeKey) key.names.toTypedArray() else key.name
            if (index.unique == true || js("JSON.stringify(actual)") != js("JSON.stringify(expected)")) throw StoreFailure.Migration()
        }
    }

    private inner class UpgradeScope(val db: dynamic, val tx: dynamic) : MigrationOperations {
        override suspend fun storeExists(name: StoreName): Boolean = db.objectStoreNames.contains(name.value) as Boolean
        override suspend fun validateStore(declaration: StoreDeclaration) { validateDeclaration(tx, declaration) }
        override suspend fun createStore(declaration: StoreDeclaration) { createStore(db, Declaration(declaration.name.value, declaration.keys, declaration.primaryKey)) }
        override suspend fun removeStore(name: StoreName) { db.deleteObjectStore(name.value) }
        override suspend fun addIndex(store: StoreName, key: StoreKey<*>) { createIndex(tx.objectStore(store.value), key) }
        override suspend fun removeIndex(store: StoreName, name: IndexName) { tx.objectStore(store.value).deleteIndex("idx_${name.value}") }
        override suspend fun transform(store: StoreName, transform: (ByteArray) -> StoreRow) {
            val target = tx.objectStore(store.value)
            val records = request(target.getAll()) as Array<dynamic>
            val converted = records.map { transform(it._value as ByteArray) }
            request(target.clear())
            converted.forEach { request(target.put(row(it.data, it.keys))) }
        }
        override suspend fun rebuildStore(store: StoreName, declaration: StoreDeclaration, transform: (ByteArray) -> StoreRow) {
            val records = request(tx.objectStore(store.value).getAll()) as Array<dynamic>
            val converted = records.map { transform(it._value as ByteArray) }
            db.deleteObjectStore(store.value)
            createStore(declaration)
            val target = tx.objectStore(declaration.name.value)
            converted.forEach { request(target.put(row(it.data, it.keys))) }
        }
    }
}
