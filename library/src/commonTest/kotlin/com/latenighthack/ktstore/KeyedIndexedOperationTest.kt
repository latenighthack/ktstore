package com.latenighthack.ktstore

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KeyedIndexedOperationTest {
    private val id = StoreKey.IntegerKey("id")
    private val rows = StoreName("rows")
    private val declaration = StoreDeclaration(rows, listOf(id), id)
    private suspend fun database() = Database(DatabaseConfiguration("indexed-keyed-${kotlin.random.Random.nextLong()}", 1,
        listOf(declaration)), InMemoryStoreDelegate()).also { it.open() }
    private suspend fun count(db: Database) = db.count(rows, IndexedQuery(id, 1))
    private suspend fun query(db: Database) = db.query(rows, IndexedQuery(id, 1))
    private suspend fun prune(db: Database) = db.deleteBatch(rows, IndexedQuery(id, 1))
    @Test fun indexedQuotaAndPruningParticipateInLogicalCommitAndRollback() = runTest {
        val db = database()
        try {
            db.transaction(setOf(rows)) { save(rows, StoreRow(byteArrayOf(7), listOf(id.bind(1)))) }
            db.transaction("quota-and-prune") {
                assertEquals(1, count(db)); assertEquals(1, query(db).records.size)
            }
            assertFailsWith<IllegalStateException> {
                db.transaction("quota-and-prune") { assertEquals(1, prune(db)); error("rollback") }
            }
            assertEquals(1, count(db))
            db.transaction("quota-and-prune") { assertEquals(1, prune(db)) }
            assertEquals(0, count(db))
        } finally { db.close() }
    }

    private class HookDelegate(val memory: InMemoryStoreDelegate = InMemoryStoreDelegate()) :
        LifecycleStoreDelegate by memory, ScopedStoreDelegate, IndexedQueryDelegate {
        var queryHook: (suspend () -> Unit)? = null
        var countHook: (suspend () -> Unit)? = null
        override suspend fun <T> transaction(block: suspend () -> T) = memory.transaction(block)
        override suspend fun <T> transaction(lockKey: String, block: suspend () -> T) = memory.transaction(lockKey, block)
        override suspend fun <T> transaction(stores: Set<String>, mode: TransactionMode, block: suspend () -> T) =
            memory.transaction(stores, mode, block)
        override suspend fun query(tableName: String, query: IndexedQuery, identity: String, version: Int): QueryPage {
            val hook = queryHook; queryHook = null; hook?.invoke()
            return memory.query(tableName, query, identity, version)
        }
        override suspend fun count(tableName: String, query: IndexedQuery): Long {
            countHook?.invoke()
            return memory.count(tableName, query)
        }
        override suspend fun deleteBatch(tableName: String, query: IndexedQuery, identity: String, version: Int) =
            memory.deleteBatch(tableName, query, identity, version)
    }

    @Test fun helperCannotBypassReadonlyOrNestedStoreBoundsEvenWhenCaught() = runTest {
        val other = StoreName("other")
        val delegate = HookDelegate()
        val db = Database(DatabaseConfiguration("indexed-bounds", 1, listOf(declaration,
            StoreDeclaration(other, listOf(id), id))), delegate)
        db.open()
        try {
            db.transaction(setOf(rows)) { save(rows, StoreRow(byteArrayOf(7), listOf(id.bind(1)))) }
            delegate.queryHook = { assertFailsWith<StoreFailure.InvalidUsage> { db.deleteBatch(rows, IndexedQuery(id, 1)) } }
            assertFailsWith<StoreFailure.Aborted> {
                db.transaction(setOf(rows), TransactionMode.READ_ONLY) { query(rows, IndexedQuery(id, 1)) }
            }
            assertEquals(1, count(db))
            delegate.queryHook = { assertFailsWith<StoreFailure.InvalidUsage> { db.count(other, IndexedQuery(id, 1)) } }
            assertFailsWith<StoreFailure.InvalidUsage> {
                db.transaction(setOf(rows, other)) {
                    save(other, StoreRow(byteArrayOf(8), listOf(id.bind(2))))
                    transaction(setOf(rows), TransactionMode.READ_ONLY) { query(rows, IndexedQuery(id, 1)) }
                }
            }
            assertEquals(0, db.count(other, IndexedQuery(id, 1)))
        } finally { db.close() }
    }

    @Test fun caughtInvalidIndexAbortsLogicalWritesAndParallelHelpersAreRejected() = runTest {
        val delegate = HookDelegate()
        val db = Database(DatabaseConfiguration("indexed-parallel", 1, listOf(declaration)), delegate)
        db.open()
        try {
            assertFailsWith<StoreFailure.Aborted> {
                db.transaction("quota") {
                    delegate.save(rows.value, byteArrayOf(1), listOf(id.bind(1)))
                    assertFailsWith<StoreFailure.InvalidUsage> { db.count(rows, IndexedQuery(StoreKey.IntegerKey("missing"), 1)) }
                }
            }
            assertEquals(0, count(db))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            delegate.countHook = { entered.complete(Unit); release.await() }
            assertFailsWith<StoreFailure.Aborted> {
                db.transaction("parallel") {
                    coroutineScope {
                        val first = async { count(db) }
                        entered.await()
                        assertFailsWith<StoreFailure.InvalidUsage> { count(db) }
                        release.complete(Unit)
                        assertEquals(0, first.await())
                    }
                }
            }
            delegate.countHook = null
            assertEquals(0, count(db))
        } finally { db.close() }
    }
}
