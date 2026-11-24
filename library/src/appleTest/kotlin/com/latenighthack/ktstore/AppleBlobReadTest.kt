package com.latenighthack.ktstore

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Blob columns come back byte-exact, from empty to large. */
class AppleBlobReadTest {
    @Test
    fun blobsRoundTripByteExactAtEverySize() = runBlocking {
        val driver = SqliteDriver(NSTemporaryDirectory() + "blob-read-${Random.nextLong()}.db")
        try {
            driver.createTable("CREATE TABLE blobs (id INTEGER PRIMARY KEY, value BLOB NOT NULL)")
            val values = listOf(0, 1, 7, 4_096, 83_348, 1_048_576).map { Random.nextBytes(it) }
            values.forEachIndexed { index, value ->
                driver.execute("INSERT INTO blobs (id, value) VALUES (?, ?)").apply {
                    bindInt(0, index.toLong()); bindBytes(1, value); step(); finalize()
                }
            }
            val select = driver.selectAll("SELECT value FROM blobs ORDER BY id")
            val read = mutableListOf<ByteArray>()
            while (select.step()) read += select.getBytes(0)
            select.finalize()
            assertEquals(values.size, read.size)
            values.zip(read).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
        } finally { driver.deleteDatabase() }
    }
}
