package com.latenighthack.ktstore

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteProgram
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.*

/** Legacy constructor retains the app database directory. New callers can supply an absolute file. */
class SqliteStoreDelegate(context: Context, dbName: String) : ScopedStoreDelegate, LifecycleStoreDelegate {
    private val delegate = SqlStoreDelegate(AndroidSqliteDriver(context.getDatabasePath(dbName)), "BLOB", legacyBinaryText = true)
    override val isSerialized = true
    override suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?) = delegate.registerStore(tableName, keys, primaryKey)
    override suspend fun createStores() = delegate.createStores()
    override suspend fun destroyStores() = delegate.destroyStores()
    override suspend fun close() = delegate.close()
    override suspend fun deleteDatabase() = delegate.deleteDatabase()
    override suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>) = delegate.save(tableName, data, keys)
    override suspend fun saveAll(tableName: String, rows: List<StoreRow>) = delegate.saveAll(tableName, rows)
    override suspend fun get(tableName: String, relation: StoreRelation?) = delegate.get(tableName, relation)
    override suspend fun getAll(tableName: String, relation: StoreRelation?) = delegate.getAll(tableName, relation)
    override suspend fun getMany(tableName: String, relations: List<StoreRelation>) = delegate.getMany(tableName, relations)
    override suspend fun delete(tableName: String, relation: StoreRelation) = delegate.delete(tableName, relation)
    override suspend fun deleteMany(tableName: String, relations: List<StoreRelation>) = delegate.deleteMany(tableName, relations)
    override suspend fun deleteAll(tableName: String) = delegate.deleteAll(tableName)
    override suspend fun <T> transaction(block: suspend () -> T) = delegate.transaction(block)
    override suspend fun <T> transaction(lockKey: String, block: suspend () -> T) = delegate.transaction(lockKey, block)
    override suspend fun <T> transaction(stores: Set<String>, mode: TransactionMode, block: suspend () -> T) = delegate.transaction(stores, mode, block)
}

class AndroidSqliteDriver(private val file: File, private val busyTimeoutMillis: Int = 5_000) : ManagedSqlDriver {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "ktstore-sqlite").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val mutex = Mutex()
    @Volatile private var closed = false
    private var connection: SQLiteDatabase? = null
    private class Tx(val owner: AndroidSqliteDriver) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Tx>
        var failure: Throwable? = null
    }
    private fun db(): SQLiteDatabase {
        if (closed) throw StoreFailure.Closed()
        return connection ?: SQLiteDatabase.openOrCreateDatabase(file, null).also {
            it.rawQuery("PRAGMA busy_timeout = $busyTimeoutMillis", emptyArray()).use { cursor -> cursor.moveToFirst() }
            connection = it
        }
    }
    private suspend fun <T> access(block: () -> T): T {
        if (closed) throw StoreFailure.Closed()
        return withContext(dispatcher) {
        if (coroutineContext[Tx]?.owner === this@AndroidSqliteDriver) block() else mutex.withLock { block() }
        }
    }
    override suspend fun <T> transaction(block: suspend () -> T): T {
        val current = coroutineContext[Tx]?.takeIf { it.owner === this }
        if (current != null) {
            try { return block() } catch (error: Throwable) { current.failure = error; throw error }
        }
        return withContext(dispatcher) {
            mutex.withLock {
                val db = db()
                val tx = Tx(this@AndroidSqliteDriver)
                db.beginTransactionNonExclusive()
                try {
                    val result = withContext(tx) { block() }
                    tx.failure?.let { throw StoreFailure.Aborted(it) }
                    currentCoroutineContext().ensureActive()
                    db.setTransactionSuccessful()
                    result
                } finally { withContext(NonCancellable) { db.endTransaction() } }
            }
        }
    }
    override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T = transaction(block)
    override suspend fun createTable(statement: String) = access { db().execSQL(statement) }
    override suspend fun dropTable(tableName: String) = createTable("DROP TABLE $tableName")
    override suspend fun execute(statement: String): SqlBoundQuery = Query(statement, false)
    override suspend fun selectAll(statement: String): SqlSelect = Query(statement, true)

    private inner class Query(val sql: String, val select: Boolean) : SqlSelect {
        val bindings = sortedMapOf<Int, Any?>()
        var cursor: Cursor? = null
        var executed = false
        var finalized = false
        override suspend fun bindNull(column: Int) { check(!finalized); bindings[column] = null }
        override suspend fun bindText(column: Int, value: String) { check(!finalized); bindings[column] = value }
        override suspend fun bindBytes(column: Int, value: ByteArray) { check(!finalized); bindings[column] = value.copyOf() }
        override suspend fun bindInt(column: Int, value: Long) { check(!finalized); bindings[column] = value }
        fun bind(program: SQLiteProgram) { bindings.forEach { (column, value) -> when (value) {
            null -> program.bindNull(column + 1)
            is String -> program.bindString(column + 1, value)
            is ByteArray -> program.bindBlob(column + 1, value)
            is Long -> program.bindLong(column + 1, value)
        } } }
        override suspend fun step(): Boolean = access {
            check(!finalized)
            if (!executed) {
                executed = true
                if (select) {
                    cursor = db().rawQueryWithFactory({ _, driver, editTable, query -> bind(query); SQLiteCursor(driver, editTable, query) }, sql, emptyArray(), null)
                } else {
                    db().compileStatement(sql).use { bind(it); it.execute() }
                    return@access false
                }
            }
            cursor?.moveToNext() ?: false
        }
        override suspend fun getBytes(column: Int): ByteArray = access { if (cursor!!.isNull(column)) throw StoreFailure.CorruptRecord(); cursor!!.getBlob(column) }
        override suspend fun getLong(column: Int): Long = access { cursor!!.getLong(column) }
        override suspend fun getText(column: Int): String = access { cursor!!.getString(column) }
        override suspend fun finalize() = withContext(NonCancellable + dispatcher) {
            if (!finalized) { finalized = true; cursor?.close(); cursor = null; bindings.clear() }
        }
    }
    override suspend fun close() {
        if (coroutineContext[Tx]?.owner === this) throw StoreFailure.InvalidUsage("Cannot close inside transaction")
        if (closed) return
        withContext(NonCancellable + dispatcher) { mutex.withLock { closed = true; connection?.close(); connection = null } }
        dispatcher.close()
    }
    override suspend fun deleteDatabase() {
        close()
        withContext(Dispatchers.IO) { if (file.exists() && !SQLiteDatabase.deleteDatabase(file)) throw StoreFailure.Unavailable() }
    }
}
