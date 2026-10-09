package com.latenighthack.ktstore

import kotlinx.coroutines.*
import java.sql.DriverManager
import org.junit.Assume.assumeTrue
import kotlin.test.*

private data class PgRecord(val id: Int, val revision: Long)
private fun readPgRecord(bytes: ByteArray): PgRecord = bytes.decodeToString().split("|").let { PgRecord(it[0].toInt(), it[1].toLong()) }
private fun writePgRecord(record: PgRecord) = "${record.id}|${record.revision}".encodeToByteArray()
private object PgDefinitionV1 : StoreDefinition<PgRecord>(StoreName("records"), "pg-record-v1", ::readPgRecord, ::writePgRecord) {
    val id = integerIndex(IndexName("recordId"), PgRecord::id)
    val revision = longIndex(IndexName("revision"), PgRecord::revision)
    init { primaryKey(id) }
}
private class PgRecords(database: Database) : Store<PgRecord>(database, PgDefinitionV1) {
    suspend fun put(record: PgRecord) = save(record)
    suspend fun read(id: Int) = get(PgDefinitionV1.id.eq(id))
}

class PostgresDefinitionMigrationTest {
    private fun postgresUrl(): String? {
        val url = System.getenv("KTSTORE_TEST_PG_URL")
        if (System.getenv("KTSTORE_TEST_PG_REQUIRED") == "true")
            require(!url.isNullOrBlank()) { "KTSTORE_TEST_PG_URL is required by the PostgreSQL conformance gate" }
        assumeTrue("KTSTORE_TEST_PG_URL is required for PostgreSQL verification", !url.isNullOrBlank())
        return url
    }

    @Test fun legacyAdoptionReopenRollbackAndConcurrentHandles() = runBlocking {
        val base = postgresUrl()
        val schema = "ktstore_migration_${System.nanoTime()}"
        DriverManager.getConnection(base).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        val url = base!! + (if ('?' in base) "&" else "?") + "currentSchema=$schema"
        val handles = mutableListOf<Database>()
        try {
            val legacy = SqlStoreDelegate(JdbcDriver(url.removePrefix("jdbc:postgresql:"), "postgresql"), "BYTEA")
            val id = StoreKey.IntegerKey("recordId")
            val revision = StoreKey.LongKey("revision")
            legacy.registerStore("records", listOf(id, revision), id)
            legacy.createStores()
            // Reproduce the historical INTEGER representation before adopting BIGINT.
            DriverManager.getConnection(url).use { c -> c.createStatement().use {
                it.execute("ALTER TABLE records ALTER COLUMN revision TYPE INTEGER")
            } }
            legacy.save("records", "1|42".encodeToByteArray(), listOf(id.bind(1), revision.bind(42L)))
            legacy.close()
            fun handle() = createPostgresDatabase(definitionDatabaseConfiguration(schema, listOf(PgDefinitionV1)), url).also(handles::add)
            val a = handle(); val b = handle()
            coroutineScope { listOf(async { a.open() }, async { b.open() }).awaitAll() }
            val ra = PgRecords(a); val rb = PgRecords(b)
            assertEquals(PgRecord(1, 42), ra.read(1))
            a.transaction("same-record") {
                ra.put(PgRecord(1, Long.MIN_VALUE))
                assertEquals(Long.MIN_VALUE, ra.read(1)?.revision)
                a.transaction("same-record") { ra.put(PgRecord(1, Long.MAX_VALUE)) }
            }
            assertEquals(PgRecord(1, Long.MAX_VALUE), rb.read(1))
            assertFailsWith<IllegalStateException> {
                a.transaction("same-record") { ra.put(PgRecord(1, 0)); error("rollback") }
            }
            assertEquals(Long.MAX_VALUE, rb.read(1)?.revision)
            a.close(); b.close()
            val reopened = handle(); reopened.open()
            assertEquals(Long.MAX_VALUE, PgRecords(reopened).read(1)?.revision)
        } finally {
            handles.forEach { it.close() }
            DriverManager.getConnection(base).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    @Test fun failedMigrationRollsBackPayloadSchemaAndVersion() = runBlocking {
        val base = postgresUrl()
        val schema = "ktstore_rollback_${System.nanoTime()}"
        DriverManager.getConnection(base).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        val url = base!! + (if ('?' in base) "&" else "?") + "currentSchema=$schema"
        val config = definitionDatabaseConfiguration(schema, listOf(PgDefinitionV1))
        val original = createPostgresDatabase(config, url)
        try {
            original.open(); PgRecords(original).put(PgRecord(1, 42)); original.close()
            val failed = createPostgresDatabase(config.copy(version = 4, migrations = config.migrations + DatabaseMigration(3, 4) {
                transform(PgDefinitionV1.storeName) { StoreRow("1|0".encodeToByteArray(), listOf(PgDefinitionV1.id.key.bind(1), PgDefinitionV1.revision.key.bind(0L))) }
                error("injected migration failure")
            }), url)
            try { assertFailsWith<StoreFailure.Migration> { failed.open() } } finally { failed.close() }
            val reopened = createPostgresDatabase(config, url)
            try { reopened.open(); assertEquals(PgRecord(1, 42), PgRecords(reopened).read(1)) } finally { reopened.close() }
        } finally {
            original.close()
            DriverManager.getConnection(base).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
    @Test fun keyedTransactionsSerializeIndependentHandlesAndPreserveDecoration() = runBlocking {
        val base = postgresUrl()!!
        val schema = "ktstore_serial_${System.nanoTime()}"
        DriverManager.getConnection(base).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        val url = base + (if ('?' in base) "&" else "?") + "currentSchema=$schema"
        val config = definitionDatabaseConfiguration(schema, listOf(PgDefinitionV1))
        var decorated = false
        val a = createPostgresDatabase(config, url) { inner ->
            decorated = true
            object : LifecycleStoreDelegate by inner, TransactionalStoreDelegate {
                override suspend fun <T> transaction(block: suspend () -> T): T = (inner as TransactionalStoreDelegate).transaction(block)
                override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T = (inner as TransactionalStoreDelegate).transaction(lockKey, block)
            }
        }
        val b = createPostgresDatabase(config, url)
        try {
            coroutineScope { awaitAll(async { a.open() }, async { b.open() }) }
            assertTrue(decorated)
            val ra = PgRecords(a); val rb = PgRecords(b)
            ra.put(PgRecord(1, 0))
            // Read/modify/write would lose updates without the cross-connection advisory lock.
            coroutineScope {
                listOf(a to ra, b to rb).map { (db, records) -> async(Dispatchers.Default) {
                    repeat(20) { db.transaction("revision-$schema") {
                        val previous = records.read(1)!!
                        records.put(previous.copy(revision = previous.revision + 1))
                    } }
                } }.awaitAll()
            }
            assertEquals(40L, rb.read(1)?.revision)
            assertFailsWith<StoreFailure.Aborted> {
                a.transaction("revision-$schema") {
                    try { a.transaction("revision-$schema") { ra.put(PgRecord(1, -1)); error("nested failure") } }
                    catch (_: IllegalStateException) { }
                }
            }
            assertEquals(40L, rb.read(1)?.revision)
        } finally {
            a.close(); b.close()
            DriverManager.getConnection(base).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    @Test fun rejectsPhysicalSchemaMismatchWithoutChangingRecords() = runBlocking {
        val base = postgresUrl()!!
        val schema = "ktstore_mismatch_${System.nanoTime()}"
        DriverManager.getConnection(base).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        val url = base + (if ('?' in base) "&" else "?") + "currentSchema=$schema"
        val config = definitionDatabaseConfiguration(schema, listOf(PgDefinitionV1))
        val original = createPostgresDatabase(config, url)
        try {
            original.open(); PgRecords(original).put(PgRecord(1, 42)); original.close()
            DriverManager.getConnection(url).use { c -> c.createStatement().use {
                it.execute("DROP INDEX idx_records_revision")
            } }
            val invalid = createPostgresDatabase(config, url)
            try { assertFailsWith<StoreFailure.Migration> { invalid.open() } } finally { invalid.close() }
            DriverManager.getConnection(url).use { c -> c.createStatement().use {
                it.executeQuery("SELECT version FROM ktstore_schema WHERE id = 1").use { rs ->
                    assertTrue(rs.next()); assertEquals(3, rs.getInt(1))
                }
                it.executeQuery("SELECT revision FROM records").use { rs ->
                    assertTrue(rs.next()); assertEquals(42L, rs.getLong(1))
                }
            } }
        } finally {
            original.close()
            DriverManager.getConnection(base).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

}
