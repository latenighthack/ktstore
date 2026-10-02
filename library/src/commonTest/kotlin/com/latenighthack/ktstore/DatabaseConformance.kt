package com.latenighthack.ktstore

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import kotlin.test.*

abstract class DatabaseConformance {
    protected val id = StoreKey.StringKey("id")
    protected val group = StoreKey.StringKey("group_id")
    protected val first = StoreName("first_store")
    protected val second = StoreName("second_store")
    protected fun configuration(identity: String) = DatabaseConfiguration(identity, 1, listOf(first, second).map { StoreDeclaration(it, listOf(id, group), id) })
    protected abstract fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate
    private fun row(idValue: String, groupValue: String = "same") = StoreRow(idValue.encodeToByteArray(), listOf(id.bind(idValue), group.bind(groupValue)))

    @Test fun wholeReadsUseLogicalPrimaryOrdering() = runTest { withContext(Dispatchers.Default) {
        val longKey = StoreKey.LongKey("id")
        val config = DatabaseConfiguration("ordering-${kotlin.random.Random.nextInt()}", 1, listOf(
            StoreDeclaration(first, listOf(id, group), id),
            StoreDeclaration(second, listOf(longKey, group), longKey),
        ))
        val delegate = backend(config)
        val db = Database(config, delegate)
        try {
            db.open()
            db.transaction(setOf(first, second)) {
                val strings = listOf("\uE000", "\uD800\uDC00", "a")
                strings.forEach { save(first, row(it)) }
                listOf(10L, -2L, 2L, Long.MIN_VALUE, Long.MAX_VALUE).forEach {
                    save(second, StoreRow(it.toString().encodeToByteArray(), listOf(longKey.bind(it), group.bind("same"))))
                }
                assertEquals(strings.sorted(), getAll(first).map { (it as ByteArray).decodeToString() })
                assertEquals(listOf(Long.MIN_VALUE, -2L, 2L, 10L, Long.MAX_VALUE), getAll(second).map { (it as ByteArray).decodeToString().toLong() })
            }
        } finally { delegate.deleteDatabase() }
    } }

    @Test fun commitRollbackAndSecondaryDeletion() = runTest { withContext(Dispatchers.Default) {
        val config = configuration("conformance-${kotlin.random.Random.nextInt()}")
        val backend = backend(config)
        val db = Database(config, backend)
        try {
            db.open()
            db.transaction(setOf(first, second)) {
                save(first, row("one")); save(second, row("two"))
                assertContentEquals("one".encodeToByteArray(), get(first) as ByteArray)
            }
            assertFailsWith<StoreFailure.Aborted> {
                db.transaction(setOf(first, second)) {
                    clear(first)
                    try { transaction(setOf(second)) { save(second, row("three")); error("fail") } }
                    catch (_: IllegalStateException) { }
                }
            }
            db.transaction(setOf(first, second)) {
                assertEquals(1, getAll(first).size)
                assertEquals(1, getAll(second).size)
                delete(second, StoreRelation.Eq(group.bind("same")))
                assertTrue(getAll(second).isEmpty())
            }
            db.close()
            db.close()
            assertFailsWith<StoreFailure.Closed> { db.transaction(setOf(first)) { get(first) } }
        } finally { backend.deleteDatabase() }
    } }

    @Test fun readonlyAndUndeclaredStoreAccessRollback() = runTest { withContext(Dispatchers.Default) {
        val config = configuration("scope-${kotlin.random.Random.nextInt()}")
        val backend = backend(config)
        val db = Database(config, backend)
        try {
            db.open()
            assertFailsWith<StoreFailure.InvalidUsage> { db.transaction(setOf(first), TransactionMode.READ_ONLY) { save(first, row("bad")) } }
            assertFailsWith<StoreFailure.InvalidUsage> { db.transaction(setOf(first)) { save(second, row("bad")) } }
            db.transaction(setOf(first, second), TransactionMode.READ_ONLY) { assertTrue(getAll(first).isEmpty()); assertTrue(getAll(second).isEmpty()) }
        } finally { backend.deleteDatabase() }
    } }

    @Test fun boundedQueriesUsePrimaryTieBreakerAndSparseNulls() = runTest { withContext(Dispatchers.Default) {
        val pk = StoreKey.IntegerKey("id")
        val order = StoreKey.IntegerKey("position", nullable = true)
        val config = DatabaseConfiguration("query-${kotlin.random.Random.nextInt()}", 1, listOf(StoreDeclaration(first, listOf(pk, order), pk)))
        val delegate = backend(config)
        val db = Database(config, delegate)
        try {
            db.open()
            db.transaction(setOf(first)) {
                saveAll(first, listOf(5, 1, 4, 2, 3).map { StoreRow(byteArrayOf(it.toByte()), listOf(pk.bind(it), order.bind(if (it == 5) null else it / 2))) })
                val query = IndexedQuery(order, 2)
                val a = query(first, query)
                assertEquals(listOf(1, 2), a.records.map { (it as ByteArray)[0].toInt() })
                val b = query(first, query.copy(after = a.continuation))
                assertEquals(listOf(3, 4), b.records.map { (it as ByteArray)[0].toInt() })
                assertNull(b.continuation)
                assertEquals(4L, count(first, query))
                val reversed = query(first, query.copy(direction = SortDirection.DESCENDING))
                assertEquals(listOf(4, 3), reversed.records.map { (it as ByteArray)[0].toInt() })
                assertEquals(2, deleteBatch(first, query.copy(upper = QueryBound(order.bind(1)))))
                assertEquals(2L, count(first, query))
                assertEquals(3, getAll(first).size)
            }
        } finally { delegate.deleteDatabase() }
    } }

    @Test fun binaryCompositeKeysAndAtomicBatches() = runTest { withContext(Dispatchers.Default) {
        val bytes = StoreKey.SerializedKey("binary_id")
        val component = StoreKey.IntegerKey("part")
        val composite = StoreKey.CompositeKey("compound", listOf(bytes.name, component.name))
        val config = DatabaseConfiguration("binary-${kotlin.random.Random.nextInt()}", 1, listOf(StoreDeclaration(first, listOf(bytes, component, composite), composite)))
        val db = Database(config, backend(config))
        fun row(i: Int): StoreRow {
            val parts = listOf(bytes.bind(byteArrayOf(0, -1, 2)), component.bind(i))
            return StoreRow(byteArrayOf(i.toByte()), parts)
        }
        try {
            db.open()
            db.transaction(setOf(first)) {
                saveAll(first, (0..129).map { row(it) })
                val key = composite.bind(listOf(bytes.bind(byteArrayOf(0, -1, 2)), component.bind(65)))
                assertContentEquals(byteArrayOf(65), get(first, StoreRelation.Eq(key)) as ByteArray)
                delete(first, StoreRelation.Eq(bytes.bind(byteArrayOf(0, -1, 2))))
                assertTrue(getAll(first).isEmpty())
            }
            assertFailsWith<CancellationException> {
                db.transaction(setOf(first)) { saveAll(first, (0..129).map { row(it) }); throw CancellationException("cancel before commit") }
            }
            db.transaction(setOf(first)) { assertTrue(getAll(first).isEmpty()) }
        } finally { db.deleteDatabase() }
    } }

    @Test fun concurrentReadModifyWriteIsSerialized() = runTest { withContext(Dispatchers.Default) {
        val config = configuration("concurrent-${kotlin.random.Random.nextInt()}")
        val db = Database(config, backend(config))
        try {
            db.open()
            coroutineScope {
                List(20) { launch {
                    db.transaction(setOf(first)) {
                        val before = (get(first) as? ByteArray)?.get(0)?.toInt() ?: 0
                        save(first, StoreRow(byteArrayOf((before + 1).toByte()), listOf(id.bind("one"), group.bind("same"))))
                    }
                } }.joinAll()
            }
            db.transaction(setOf(first)) { assertEquals(20, (get(first) as ByteArray)[0].toInt()) }
        } finally { db.deleteDatabase() }
    } }

    @Test fun typedWrappersPreserveNominalKeysAndNulls() = runTest { withContext(Dispatchers.Default) {
        val config = DatabaseConfiguration("typed-${kotlin.random.Random.nextInt()}", 1,
            listOf(StoreDeclaration(first, listOf(id, StoreKey.StringKey("account", nullable = true)), id)))
        val db = Database(config, backend(config))
        val store = RequestStore(db, first)
        try {
            db.open()
            db.transaction(setOf(first)) {
                val record = RequestRecord(RequestId("request-1"), null)
                save(store, record)
                assertEquals(record, get(store, store.idIndex.eq(RequestId("request-1"))))
                assertTrue(getAll(store, store.accountIndex.eq(RequestId(""))).isEmpty())
            }
        } finally { db.deleteDatabase() }
    } }

    @Test fun orderedLongBoundaries() {
        val values = listOf(Long.MIN_VALUE, -100, -1, 0, 1, 9, 10, Long.MAX_VALUE)
        assertEquals(values, values.reversed().sortedWith { a, b -> OrderedKeyEncoding.long(a).compareTo(OrderedKeyEncoding.long(b)) })
    }
}
