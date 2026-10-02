package com.latenighthack.ktstore

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

abstract class PersistentConformance : DatabaseConformance() {
    @Test fun independentConnectionsAndDeletionInvalidateOldHandles() = runTest { withContext(Dispatchers.Default) {
        val config = configuration("connections-${kotlin.random.Random.nextInt()}")
        val firstHandle = Database(config, backend(config))
        val secondHandle = Database(config, backend(config))
        firstHandle.open(); secondHandle.open()
        coroutineScope {
            listOf(firstHandle, secondHandle).map { db -> launch {
                repeat(10) { db.transaction(setOf(first)) {
                    val old = (get(first) as? ByteArray)?.get(0)?.toInt() ?: 0
                    save(first, StoreRow(byteArrayOf((old + 1).toByte()), listOf(id.bind("one"), group.bind("same"))))
                } }
            } }.joinAll()
        }
        secondHandle.transaction(setOf(first)) { assertEquals(20, (get(first) as ByteArray)[0].toInt()) }
        firstHandle.deleteDatabase()
        assertFailsWith<StoreFailure.Closed> { secondHandle.transaction(setOf(first)) { get(first) } }
        val fresh = Database(config, backend(config))
        try { fresh.open(); fresh.transaction(setOf(first)) { assertNull(get(first)) } }
        finally { fresh.deleteDatabase() }
    } }

    @Test fun migrationFailurePreservesOldDataAndVersion() = runTest { withContext(Dispatchers.Default) {
        val config = configuration("migration-${kotlin.random.Random.nextInt()}")
        val original = Database(config, backend(config))
        original.open()
        original.transaction(setOf(first)) { save(first, StoreRow(byteArrayOf(1), listOf(id.bind("one"), group.bind("g")))) }
        original.close()
        val bad = config.copy(version = 2, migrations = listOf(DatabaseMigration(1, 2) {
            transform(first) { StoreRow(byteArrayOf(2), listOf(id.bind("one"), group.bind("g"))) }
            removeStore(second)
            error("migration failed")
        }))
        val failed = Database(bad, backend(bad))
        try { assertFailsWith<StoreFailure.Migration> { failed.open() } } finally { failed.close() }
        val reopened = Database(config, backend(config))
        reopened.open()
        reopened.transaction(setOf(first, second), TransactionMode.READ_ONLY) {
            assertContentEquals(byteArrayOf(1), get(first) as ByteArray)
            assertTrue(getAll(second).isEmpty())
        }
        reopened.close()
        val good = config.copy(version = 2, migrations = listOf(DatabaseMigration(1, 2) {
            transform(first) { StoreRow(byteArrayOf(3), listOf(id.bind("one"), group.bind("g"))) }
        }))
        val upgraded = Database(good, backend(good))
        try {
            upgraded.open()
            upgraded.transaction(setOf(first), TransactionMode.READ_ONLY) { assertContentEquals(byteArrayOf(3), get(first) as ByteArray) }
        } finally { upgraded.deleteDatabase() }
        val fresh = Database(config, backend(config))
        try {
            fresh.open()
            fresh.transaction(setOf(first), TransactionMode.READ_ONLY) { assertTrue(getAll(first).isEmpty()) }
        } finally { fresh.deleteDatabase() }
    } }
}
