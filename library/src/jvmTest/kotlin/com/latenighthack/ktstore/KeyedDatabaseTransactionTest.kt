package com.latenighthack.ktstore

import java.io.File
import kotlinx.coroutines.*
import kotlin.test.*

abstract class KeyedDatabaseTransactions {
    protected abstract fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate
    private val definition = example.UsersV2
    private fun configuration() = definitionDatabaseConfiguration("keyed-${System.nanoTime()}", listOf(definition))

    @Test fun commitsNestedTypedWritesAndRollsBackCaughtNestedFailure(): Unit = runBlocking {
        val config = configuration()
        val db = Database(config, backend(config))
        val users = example.UsersStore(db)
        try {
            assertFailsWith<StoreFailure.InvalidUsage> { db.transaction("users") {} }
            db.open(); users.prepare()
            db.transaction("users") {
                users.put(example.UserV2(1, "before", true))
                db.transaction("users") { users.put(example.UserV2(2, "nested", true)) }
            }
            assertEquals(2, users.findByName("nested").single().id)
            assertFailsWith<StoreFailure.Aborted> {
                db.transaction("users") {
                    try { db.transaction("users") { users.put(example.UserV2(1, "after", true)); error("failure") } }
                    catch (_: IllegalStateException) { }
                }
            }
            assertEquals("before", users.findByName("before").single().name)
            assertFailsWith<CancellationException> {
                db.transaction("users") { users.put(example.UserV2(3, "cancelled", true)); throw CancellationException() }
            }
            assertTrue(users.findByName("cancelled").isEmpty())
            db.close()
            assertFailsWith<StoreFailure.Closed> { db.transaction("users") {} }
        } finally { db.deleteDatabase() }
    }

    @Test fun rejectsOtherHandlesAndScopedNesting(): Unit = runBlocking {
        val config = configuration()
        val inner = backend(config)
        lateinit var db: Database
        var attemptScopedNesting = false
        val wrapper = object : LifecycleStoreDelegate by inner, ScopedStoreDelegate {
            override suspend fun <T> transaction(block: suspend () -> T): T = (inner as TransactionalStoreDelegate).transaction(block)
            override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T = (inner as TransactionalStoreDelegate).transaction(lockKey, block)
            override suspend fun <T> transaction(stores: Set<String>, mode: TransactionMode, block: suspend () -> T): T = (inner as ScopedStoreDelegate).transaction(stores, mode, block)
            override suspend fun getAll(tableName: String, relation: StoreRelation?): List<Any> {
                if (attemptScopedNesting) db.transaction("users") {}
                return inner.getAll(tableName, relation)
            }
        }
        db = Database(config, wrapper)
        val otherConfig = configuration()
        val other = Database(otherConfig, backend(otherConfig))
        try {
            db.open(); other.open()
            db.transaction("users") {
                assertFailsWith<StoreFailure.InvalidUsage> { other.transaction("users") {} }
                assertFailsWith<StoreFailure.InvalidUsage> { db.transaction(setOf(definition.storeName)) {} }
            }
            attemptScopedNesting = true
            assertFailsWith<StoreFailure.InvalidUsage> {
                db.transaction(setOf(definition.storeName), TransactionMode.READ_ONLY) { getAll(definition.storeName) }
            }
        } finally { db.deleteDatabase(); other.deleteDatabase() }
    }

    @Test fun closeCancelsTransactionWithoutCancellingItsCaller(): Unit = runBlocking {
        val config = configuration()
        val inner = backend(config)
        var commits = 0
        val wrapper = object : LifecycleStoreDelegate by inner, TransactionalStoreDelegate {
            override suspend fun <T> transaction(block: suspend () -> T): T = (inner as TransactionalStoreDelegate).transaction(block)
            override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T =
                (inner as TransactionalStoreDelegate).transaction(lockKey, block).also { commits++ }
        }
        val db = Database(config, wrapper)
        val users = example.UsersStore(db)
        val entered = CompletableDeferred<Unit>()
        try {
            db.open(); users.prepare()
            // The closer is a sibling of the transaction; close must not cancel runBlocking.
            val closer = launch { entered.await(); db.close() }
            assertFailsWith<CancellationException> {
                db.transaction("users") {
                    users.put(example.UserV2(1, "uncommitted", true))
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            closer.join()
            ensureActive()
            assertEquals(0, commits)
        } finally { db.deleteDatabase() }
    }
}

class MemoryKeyedDatabaseTransactionTest : KeyedDatabaseTransactions() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate = InMemoryStoreDelegate()
}
class JdbcKeyedDatabaseTransactionTest : KeyedDatabaseTransactions() {
    override fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate =
        SqlStoreDelegate(JdbcDriver(File(System.getProperty("java.io.tmpdir"), config.identity + ".db").absolutePath, "sqlite"), "BLOB", config)
}
