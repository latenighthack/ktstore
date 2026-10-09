package com.latenighthack.ktstore

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LegacyPrimaryCountTest {
    @Test fun binaryIndexedCountDoesNotRequireOrderingLegacyPrimaryValues(): Unit = runBlocking {
        val file = File.createTempFile("legacy-count", ".db")
        val primary = StoreKey.LongKey("legacy")
        val room = StoreKey.SerializedKey("room")
        val rows = StoreName("rows")
        val db = createDatabase(DatabaseConfiguration("legacy-count-${file.name}", 1,
            listOf(StoreDeclaration(rows, listOf(primary, room), primary))), file.absolutePath)
        db.open()
        try {
            db.transaction(setOf(rows)) {
                save(rows, StoreRow(byteArrayOf(9), listOf(primary.bind(Long.MAX_VALUE), room.bind(byteArrayOf(1)))))
                save(rows, StoreRow(byteArrayOf(8), listOf(primary.bind(Long.MIN_VALUE), room.bind(byteArrayOf(2)))))
            }
            db.transaction("quota") {
                assertEquals(2, db.count(rows, IndexedQuery(room, 1)))
                assertEquals(1, db.count(rows, IndexedQuery(room, 1, lower = QueryBound(room.bind(byteArrayOf(1))), upper = QueryBound(room.bind(byteArrayOf(1))))))
            }
            assertFailsWith<StoreFailure.InvalidUsage> { db.query(rows, IndexedQuery(room, 1)) }
        } finally { db.close(); file.delete() }
    }
}
