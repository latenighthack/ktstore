package com.latenighthack.ktstore

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.json
import kotlin.test.*

class IndexedDbLifecycleTest {
    private val factory = js("globalThis.indexedDB")
    private suspend fun request(raw: dynamic): Any? = suspendCancellableCoroutine { continuation ->
        raw.onsuccess = { _: dynamic -> continuation.resume(raw.result) }
        raw.onerror = { _: dynamic -> continuation.resumeWithException(IllegalStateException("Fixture request failed")) }
    }
    @Test fun upgradesLegacyDecimalLongFixtureWithoutChangingPayloads() = runTest { withContext(Dispatchers.Default) {
        val name = "legacy-${kotlin.random.Random.nextInt()}"
        val open = factory.open(name, 1)
        open.onupgradeneeded = { _: dynamic ->
            val store = open.result.createObjectStore("records", json("keyPath" to "id"))
            store.createIndex("idx_id", "id")
            store.createIndex("idx_time", "time")
        }
        val old = request(open).asDynamic()
        val values = listOf(Long.MIN_VALUE, -10L, -1L, 0L, 9L, 10L, Long.MAX_VALUE)
        val transaction = old.transaction("records", "readwrite")
        val done = CompletableDeferred<Unit>()
        transaction.oncomplete = { _: dynamic -> done.complete(Unit) }
        values.reversed().forEachIndexed { i, value ->
            transaction.objectStore("records").put(json("id" to i, "time" to value.toString(), "_value" to "$i,$value".encodeToByteArray()))
        }
        done.await(); old.close()
        val pk = StoreKey.IntegerKey("id")
        val time = StoreKey.SerializedKey("time")
        val records = StoreName("records")
        val config = DatabaseConfiguration(name, 2, listOf(StoreDeclaration(records, listOf(pk, time), pk)), listOf(DatabaseMigration(1, 2) {
            transform(records) { data ->
                val parts = data.decodeToString().split(',')
                StoreRow(data, listOf(pk.bind(parts[0].toInt()), time.bind(OrderedKeyEncoding.long(parts[1].toLong()))))
            }
        }))
        val db = createDatabase(config)
        try {
            db.open()
            db.transaction(setOf(records)) {
                assertEquals(values, query(records, IndexedQuery(time, 20)).records.map { (it as ByteArray).decodeToString().split(',')[1].toLong() })
            }
        } finally { db.deleteDatabase() }
    } }
    @Test fun blockedDeletionKeepsLateRequestAndReopenOrdered() = runTest { withContext(Dispatchers.Default) {
        val name = "blocked-${kotlin.random.Random.nextInt()}"
        val open = factory.open(name, 1)
        open.onupgradeneeded = { _: dynamic -> open.result.createObjectStore("records") }
        val old = request(open).asDynamic()
        val delegate = IndexDB(name)
        assertFailsWith<StoreFailure.Blocked> { delegate.deleteDatabase() }
        old.close()
        val id = StoreKey.IntegerKey("id")
        val config = DatabaseConfiguration(name, 1, listOf(StoreDeclaration(StoreName("records"), listOf(id), id)))
        val db = createDatabase(config)
        try { db.open() } finally { db.deleteDatabase() }
    } }
    @Test fun missingApiAndSilentOpenFailWithinBound() = runTest { withContext(Dispatchers.Default) {
        assertFailsWith<StoreFailure.Unavailable> { IndexDB("missing", IndexedDbBackend(null, null)).createStores() }
        val fake = js("({open: function() { return {}; }})")
        val config = DatabaseConfiguration("timeout", 1, emptyList(), operationTimeoutMillis = 25)
        val delegate = IndexDB(config, IndexedDbBackend(fake, js("({})")))
        assertFailsWith<StoreFailure.Timeout> { delegate.createStores() }
        delegate.close()
    } }
}
