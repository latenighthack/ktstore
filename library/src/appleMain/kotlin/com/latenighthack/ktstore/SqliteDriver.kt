package com.latenighthack.ktstore

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sqlite3.*
import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import platform.Foundation.*
import kotlin.coroutines.*

/** Absolute paths are app-owned. Relative paths retain the legacy Documents convention. */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class SqliteDriver(path: String, private val busyTimeoutMillis: Int = 5_000) : ManagedSqlDriver {
    private val filename = if (path.startsWith("/")) path else
        "${NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String}/$path"
    private val dispatcher = newSingleThreadContext("ktstore-sqlite")
    private val mutex = Mutex()
    private var db: CPointer<sqlite3>? = null
    private val closedFlag = AtomicBoolean(false)
    private var closed: Boolean
        get() = closedFlag.value
        set(value) { closedFlag.value = value }
    private val statements = mutableSetOf<Query>()
    private class Tx(val owner: SqliteDriver) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Tx>
        var failure: Throwable? = null
    }
    private class NativeError(val code: Int) : IllegalStateException("SQLite error code $code")
    private fun checkCode(code: Int) {
        when (code) {
            SQLITE_OK, SQLITE_DONE, SQLITE_ROW -> Unit
            SQLITE_BUSY, SQLITE_LOCKED -> throw StoreFailure.Busy(NativeError(code))
            SQLITE_CONSTRAINT -> throw StoreFailure.Constraint(NativeError(code))
            SQLITE_FULL -> throw StoreFailure.Quota(NativeError(code))
            SQLITE_CORRUPT, SQLITE_NOTADB -> throw StoreFailure.CorruptRecord(NativeError(code))
            else -> throw StoreFailure.Unavailable(NativeError(code))
        }
    }
    private fun connection(): CPointer<sqlite3> {
        if (closed) throw StoreFailure.Closed()
        db?.let { return it }
        return memScoped {
            val pointer = alloc<CPointerVar<sqlite3>>()
            val code = sqlite3_open(filename, pointer.ptr)
            if (code != SQLITE_OK) { pointer.value?.let { sqlite3_close(it) }; checkCode(code) }
            pointer.value!!.also { checkCode(sqlite3_busy_timeout(it, busyTimeoutMillis)); db = it }
        }
    }
    private suspend fun <T> access(block: () -> T): T {
        if (closed) throw StoreFailure.Closed()
        return withContext(dispatcher) {
            if (coroutineContext[Tx]?.owner === this@SqliteDriver) block() else mutex.withLock { block() }
        }
    }
    private fun executeNow(sql: String) { checkCode(sqlite3_exec(connection(), sql, null, null, null)) }
    override suspend fun <T> transaction(block: suspend () -> T): T {
        val current = coroutineContext[Tx]?.takeIf { it.owner === this }
        if (current != null) {
            try { return block() } catch (error: Throwable) { current.failure = error; throw error }
        }
        if (closed) throw StoreFailure.Closed()
        return withContext(dispatcher) {
            mutex.withLock {
                executeNow("BEGIN IMMEDIATE")
                val tx = Tx(this@SqliteDriver)
                try {
                    val result = withContext(tx) { block() }
                    tx.failure?.let { throw StoreFailure.Aborted(it) }
                    currentCoroutineContext().ensureActive()
                    executeNow("COMMIT")
                    result
                } catch (error: Throwable) {
                    withContext(NonCancellable) { runCatching { executeNow("ROLLBACK") }.exceptionOrNull()?.let { error.addSuppressed(it) } }
                    throw error
                }
            }
        }
    }
    override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T = transaction(block)
    override suspend fun createTable(statement: String) = access { executeNow(statement) }
    override suspend fun dropTable(tableName: String) = createTable("DROP TABLE $tableName")
    private suspend fun prepare(sql: String): Query = access {
        memScoped {
            val pointer = alloc<CPointerVar<sqlite3_stmt>>()
            val result = sqlite3_prepare_v2(connection(), sql, -1, pointer.ptr, null)
            if (result != SQLITE_OK) { pointer.value?.let { sqlite3_finalize(it) }; checkCode(result) }
            Query(pointer.value ?: throw StoreFailure.Unavailable()).also { statements.add(it) }
        }
    }
    override suspend fun execute(statement: String): SqlBoundQuery = prepare(statement)
    override suspend fun selectAll(statement: String): SqlSelect = prepare(statement)
    private inner class Query(private val statement: CPointer<sqlite3_stmt>) : SqlSelect {
        private var finalized = false
        private fun live() { if (finalized) throw StoreFailure.Closed() }
        override suspend fun bindNull(column: Int) = access { live(); checkCode(sqlite3_bind_null(statement, column + 1)) }
        override suspend fun bindText(column: Int, value: String) = access {
            live(); checkCode(sqlite3_bind_text(statement, column + 1, value, -1, (-1L).toCPointer<CFunction<(COpaquePointer?) -> Unit>>()))
        }
        override suspend fun bindInt(column: Int, value: Long) = access { live(); checkCode(sqlite3_bind_int64(statement, column + 1, value)) }
        override suspend fun bindBytes(column: Int, value: ByteArray) = access {
            live()
            val result = if (value.isEmpty()) sqlite3_bind_zeroblob(statement, column + 1, 0) else value.usePinned {
                sqlite3_bind_blob(statement, column + 1, it.addressOf(0), value.size, (-1L).toCPointer<CFunction<(COpaquePointer?) -> Unit>>())
            }
            checkCode(result)
        }
        override suspend fun step(): Boolean = access { live(); val result = sqlite3_step(statement); checkCode(result); result == SQLITE_ROW }
        override suspend fun getBytes(column: Int): ByteArray = access {
            live(); if (sqlite3_column_type(statement, column) == SQLITE_NULL) throw StoreFailure.CorruptRecord()
            sqlite3_column_blob(statement, column)?.readBytes(sqlite3_column_bytes(statement, column)) ?: ByteArray(0)
        }
        override suspend fun getLong(column: Int): Long = access { live(); sqlite3_column_int64(statement, column) }
        override suspend fun getText(column: Int): String = access { live(); sqlite3_column_text(statement, column)?.reinterpret<ByteVar>()?.toKString() ?: throw StoreFailure.CorruptRecord() }
        fun finalizeNow() { if (!finalized) { finalized = true; sqlite3_finalize(statement); statements.remove(this) } }
        override suspend fun finalize() {
            if (finalized) return
            withContext(NonCancellable + dispatcher) { finalizeNow() }
        }
    }
    override suspend fun close() {
        if (coroutineContext[Tx]?.owner === this) throw StoreFailure.InvalidUsage("Cannot close inside transaction")
        if (closed) return
        withContext(NonCancellable + dispatcher) {
            mutex.withLock {
                statements.toList().forEach { it.finalizeNow() }
                db?.let { checkCode(sqlite3_close(it)) }
                db = null; closed = true
            }
        }
        dispatcher.close()
    }
    override suspend fun deleteDatabase() {
        close()
        withContext(Dispatchers.Default) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val path = filename + suffix
                if (NSFileManager.defaultManager.fileExistsAtPath(path) && !NSFileManager.defaultManager.removeItemAtPath(path, null)) throw StoreFailure.Unavailable()
            }
        }
    }
}
