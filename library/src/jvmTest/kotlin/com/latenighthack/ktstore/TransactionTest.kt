package com.latenighthack.ktstore

import kotlinx.coroutines.*
import kotlin.test.*

class TransactionTest {
    private val key = StoreKey.StringKey("id")
    private suspend fun fixture(): InMemoryStoreDelegate = InMemoryStoreDelegate().also {
        for (name in listOf("lockers", "outbox")) it.registerStore(name, listOf(key), key)
        it.createStores()
    }
    private suspend fun StoreDelegate.put(table: String, value: String) = save(table, value, listOf(key.bind("one")))

    @Test fun commitsAcrossStoresAndNestedCalls() = runBlocking {
        val db = fixture()
        db.transaction {
            db.put("lockers", "source")
            db.transaction { db.put("outbox", "intent") }
        }
        assertEquals("source", db.get("lockers", null))
        assertEquals("intent", db.get("outbox", null))
    }

    @Test fun rollsBackBothStoresOnCancellation() = runBlocking {
        val db = fixture()
        db.put("lockers", "before")
        assertFailsWith<CancellationException> {
            db.transaction {
                db.put("lockers", "after")
                db.put("outbox", "intent")
                throw CancellationException("cancel")
            }
        }
        assertEquals("before", db.get("lockers", null))
        assertNull(db.get("outbox", null))
    }

    @Test fun concurrentReaderCannotObservePartialCommit() = runBlocking {
        val db = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = launch {
            db.transaction {
                db.put("lockers", "source")
                entered.complete(Unit)
                release.await()
                db.put("outbox", "intent")
            }
        }
        entered.await()
        val reader = async(start = CoroutineStart.UNDISPATCHED) { db.get("lockers", null) }
        assertFalse(reader.isCompleted)
        release.complete(Unit)
        writer.join()
        assertEquals("source", reader.await())
        assertEquals("intent", db.get("outbox", null))
    }
}
