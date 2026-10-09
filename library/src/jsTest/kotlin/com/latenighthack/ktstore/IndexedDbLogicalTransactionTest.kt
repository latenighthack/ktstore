package com.latenighthack.ktstore

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class IndexedDbLogicalTransactionTest {
    private data class Counter(val id: Int = 1, val value: Int)
    private class Definition(name: String) : StoreDefinition<Counter>(StoreName(name), "Counter-int-v1",
        { Counter(value = it.decodeToString().toInt()) }, { it.value.toString().encodeToByteArray() }) {
        val id = integerIndex(IndexName("id"), Counter::id).also { primaryKey(it) }
    }
    private class Counters(db: Database, private val schema: Definition) : Store<Counter>(db, schema) {
        suspend fun value(): Int { prepare(); return get(schema.id.eq(1))?.value ?: 0 }
        suspend fun update(value: Int) { prepare(); save(Counter(value = value)) }
    }
    @Test fun logicalOwnerSerializesTwoHandlesAndRollsBackAllStores() = runTest { withContext(Dispatchers.Default) {
        val a = Definition("counter_a"); val b = Definition("counter_b")
        val config = definitionDatabaseConfiguration("logical-${Random.nextLong()}", listOf(a, b))
        val first = createDatabase(config); val second = createDatabase(config)
        first.open(); second.open()
        try {
            val stores = listOf(first, second).map { Counters(it, a) to Counters(it, b) }
            coroutineScope { (0 until 20).map { index -> async {
                val db = if (index % 2 == 0) first else second
                val (left, right) = stores[index % 2]
                db.transaction("counter") {
                    db.transaction("nested-counter") {
                        left.update(left.value() + 1); right.update(right.value() + 1)
                    }
                    assertEquals(1L, db.count(a.storeName, IndexedQuery(a.id.key, 1)))
                }
            } }.awaitAll() }
            assertEquals(20, stores[0].first.value()); assertEquals(20, stores[1].second.value())
            assertFailsWith<IllegalStateException> { first.transaction("counter") {
                stores[0].first.update(99); stores[0].second.update(99); error("rollback")
            } }
            assertEquals(20, stores[1].first.value()); assertEquals(20, stores[1].second.value())
            assertFailsWith<StoreFailure.Aborted> {
                first.transaction(setOf(a.storeName), TransactionMode.READ_ONLY) {
                    assertFailsWith<StoreFailure.InvalidUsage> { deleteBatch(a.storeName, IndexedQuery(a.id.key, 1)) }
                }
            }
            assertEquals(20, stores[0].first.value())
        } finally { second.close(); first.deleteDatabase() }
    } }
}
