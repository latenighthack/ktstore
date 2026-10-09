package com.latenighthack.ktstore

import java.io.File
import kotlinx.coroutines.*
import kotlin.test.*

private data class LogicalRecord(val id: Int, val value: String)
private object LogicalDefinition : StoreDefinition<LogicalRecord>(
    StoreName("logical_records"), "logical-record-v1",
    { bytes -> bytes.decodeToString().split("|", limit = 2).let { LogicalRecord(it[0].toInt(), it[1]) } },
    { record -> "${record.id}|${record.value}".encodeToByteArray() },
) {
    val id = integerIndex(IndexName("id"), LogicalRecord::id)
    init { primaryKey(id) }
}
private class LogicalRecords(database: Database) : Store<LogicalRecord>(database, LogicalDefinition) {
    suspend fun put(value: String) = save(LogicalRecord(1, value))
    suspend fun read() = get(LogicalDefinition.id.eq(1))?.value
}

abstract class LogicalTransactionTest {
    protected abstract fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate
    private fun database(): Database {
        val config = DatabaseConfiguration("logical-${System.nanoTime()}", 1, listOf(LogicalDefinition.declaration))
        return Database(config, backend(config))
    }

    @Test fun requiresAnOpenHandleAndNonblankLock() = runBlocking<Unit> {
        val db = database()
        try {
            assertFailsWith<StoreFailure.InvalidUsage> { db.transaction("record") {} }
            db.open()
            assertFailsWith<IllegalArgumentException> { db.transaction(" ") {} }
            db.close()
            assertFailsWith<StoreFailure.Closed> { db.transaction("record") {} }
        } finally { db.deleteDatabase() }
    }

    @Test fun caughtNestedFailureStillRollsBackTypedStores() = runBlocking<Unit> {
        val db = database(); val records = LogicalRecords(db)
        try {
            db.open(); records.put("before")
            assertFailsWith<StoreFailure.Aborted> {
                db.transaction("record") {
                    records.put("after")
                    try { db.transaction("record") { records.put("nested"); error("rollback") } }
                    catch (_: IllegalStateException) { }
                }
            }
            assertEquals("before", records.read())
            db.transaction("record") { db.transaction("record") { records.put("committed") } }
            assertEquals("committed", records.read())
        } finally { db.deleteDatabase() }
    }

    @Test fun cancellationRollsBackAndLeavesTheHandleUsable() = runBlocking<Unit> {
        val db = database(); val records = LogicalRecords(db)
        try {
            db.open(); records.put("before")
            assertFailsWith<CancellationException> {
                db.transaction("record") { records.put("after"); throw CancellationException("cancel") }
            }
            assertEquals("before", records.read())
            db.transaction("record") { records.put("committed") }
            assertEquals("committed", records.read())
        } finally { db.deleteDatabase() }
    }

    @Test fun closeCancelsAnActiveTransactionAndCannotRunInsideIt() = runBlocking<Unit> {
        val db = database(); val records = LogicalRecords(db)
        try {
            db.open()
            db.transaction("record") {
                assertFailsWith<StoreFailure.InvalidUsage> { db.close() }
                records.put("before")
            }
            val entered = CompletableDeferred<Unit>()
            val writer = launch {
                db.transaction("record") { records.put("after"); entered.complete(Unit); awaitCancellation() }
            }
            withTimeout(10_000) { entered.await(); db.close(); writer.join() }
            assertTrue(writer.isCancelled)
            assertFailsWith<StoreFailure.Closed> { db.transaction("record") {} }
        } finally { db.deleteDatabase() }
    }
}

class MemoryLogicalTransactionTest : LogicalTransactionTest() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate = InMemoryStoreDelegate()
}
class JdbcLogicalTransactionTest : LogicalTransactionTest() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate =
        SqlStoreDelegate(JdbcDriver(File(System.getProperty("java.io.tmpdir"), config.identity + ".db").absolutePath, "sqlite"), "BLOB", config)
}
