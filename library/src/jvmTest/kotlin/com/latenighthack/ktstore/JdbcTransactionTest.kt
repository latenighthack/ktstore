package com.latenighthack.ktstore

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class JdbcTransactionTest {
    @Test fun bulkWritesRollbackAndRoundTripLargePayloads() = runBlocking {
        val file = File.createTempFile("ktstore-transaction", ".db").also { it.deleteOnExit() }
        val db = SqlStoreDelegate(JdbcDriver(file.absolutePath, "sqlite"), "BLOB")
        val key = StoreKey.StringKey("id")
        for (table in listOf("lockers", "outbox")) db.registerStore(table, listOf(key), key)
        db.createStores()
        val body = ByteArray(1_145_000) { (it % 251).toByte() }
        val rows = (0..99).map { StoreRow(if (it == 0) body else byteArrayOf(it.toByte()), listOf(key.bind("key'$it"))) }
        db.transaction { db.saveAll("lockers", rows) }
        assertContentEquals(body, db.get("lockers", StoreRelation.Eq(key.bind("key'0"))) as ByteArray)
        assertFailsWith<IllegalStateException> {
            db.transaction {
                db.deleteAll("lockers")
                db.saveAll("outbox", rows)
                error("crash before commit")
            }
        }
        assertEquals(100, db.getAll("lockers", null).size)
        assertEquals(0, db.getAll("outbox", null).size)
        db.deleteMany("lockers", (0..98).map { StoreRelation.Eq(key.bind("key'$it")) })
        assertEquals(1, db.getAll("lockers", null).size)
    }
}
