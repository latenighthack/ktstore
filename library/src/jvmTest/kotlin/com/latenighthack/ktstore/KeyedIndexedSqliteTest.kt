package com.latenighthack.ktstore

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class KeyedIndexedSqliteTest {
    @Test fun indexedPruningAndCountsShareThePhysicalSqliteTransaction() = runBlocking {
        val file = File.createTempFile("ktstore-indexed-keyed", ".db")
        val id = StoreKey.IntegerKey("id")
        val rows = StoreName("rows")
        val configuration = DatabaseConfiguration("keyed-sqlite-${file.name}", 1,
            listOf(StoreDeclaration(rows, listOf(id), id)))
        val db = createDatabase(configuration, file.absolutePath)
        db.open()
        try {
            db.transaction(setOf(rows)) { save(rows, StoreRow(byteArrayOf(3), listOf(id.bind(3)))) }
            assertFailsWith<IllegalStateException> {
                db.transaction("quota") {
                    assertEquals(1, db.count(rows, IndexedQuery(id, 1)))
                    assertContentEquals(byteArrayOf(3), db.query(rows, IndexedQuery(id, 1)).records.single() as ByteArray)
                    assertEquals(1, db.deleteBatch(rows, IndexedQuery(id, 1)))
                    error("rollback")
                }
            }
            assertEquals(1, db.count(rows, IndexedQuery(id, 1)))
            db.transaction("quota") { assertEquals(1, db.deleteBatch(rows, IndexedQuery(id, 1))) }
            assertEquals(0, db.count(rows, IndexedQuery(id, 1)))
        } finally { db.close(); file.delete() }
    }
}
