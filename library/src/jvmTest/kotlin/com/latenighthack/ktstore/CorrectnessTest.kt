package com.latenighthack.ktstore

import kotlinx.coroutines.*
import java.io.File
import kotlin.test.*

class CorrectnessTest {
    private val id = StoreKey.StringKey("id")
    private suspend fun fixture(jdbc: Boolean): TransactionalStoreDelegate {
        val delegate = if (jdbc) SqlStoreDelegate(JdbcDriver(File.createTempFile("ktstore-check", ".db").apply { deleteOnExit() }.absolutePath, "sqlite"), "BLOB") else InMemoryStoreDelegate()
        delegate.registerStore("records", listOf(id), id)
        delegate.createStores()
        return delegate
    }

    @Test fun caughtNestedFailureIsRollbackOnly() = runBlocking {
        for (jdbc in listOf(false, true)) {
            val db = fixture(jdbc)
            assertFailsWith<StoreFailure.Aborted> {
                db.transaction {
                    db.save("records", byteArrayOf(1), listOf(id.bind("one")))
                    try { db.transaction { error("inner failure") } } catch (_: IllegalStateException) { }
                }
            }
            assertTrue(db.getAll("records", null).isEmpty())
        }
    }

    @Test fun laterChunkFailureRollsBackEarlierChunks() = runBlocking {
        val db = fixture(true)
        val rows = (0..129).map { StoreRow(byteArrayOf(1), listOf(id.bind("id$it"))) }.toMutableList()
        rows[129] = StoreRow(byteArrayOf(2), listOf(StoreKey.StringKey("missing").bind("invalid")))
        assertFails { db.saveAll("records", rows) }
        assertTrue(db.getAll("records", null).isEmpty())
    }

    @Test fun bulkReadsRetainRepeatedRelations() = runBlocking {
        for (jdbc in listOf(false, true)) {
            val db = fixture(jdbc)
            db.save("records", byteArrayOf(1), listOf(id.bind("one")))
            assertEquals(2, db.getMany("records", List(2) { StoreRelation.Eq(id.bind("one")) }).size)
        }
    }

    @Test fun preparationWaitsForRegistrationAndCanRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var attempts = 0
        val delegate = object : StoreDelegate by InMemoryStoreDelegate() {
            override suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?) {
                attempts++
                entered.complete(Unit)
                finish.await()
                if (attempts == 1) error("registration failed")
            }
        }
        val store = Store(delegate, "records", ::encode, ::decode)
        supervisorScope {
            val first = async { runCatching { store.prepare() } }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { store.prepare() } }
            assertFalse(second.isCompleted)
            finish.complete(Unit)
            assertTrue(first.await().isFailure)
            assertTrue(second.await().isFailure)
        }
        store.prepare()
        store.prepare()
        assertEquals(2, attempts)
    }
}
private fun encode(value: String) = value.encodeToByteArray()
private fun decode(value: ByteArray) = value.decodeToString()
