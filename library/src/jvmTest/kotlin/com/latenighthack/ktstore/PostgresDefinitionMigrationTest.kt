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
    @Test fun legacyAdoptionReopenRollbackAndConcurrentHandles() = runBlocking {
        val base = System.getenv("KTSTORE_TEST_PG_URL")
        assumeTrue("KTSTORE_TEST_PG_URL is required for PostgreSQL verification", base != null)
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
            legacy.save("records", "1|42".encodeToByteArray(), listOf(id.bind(1), revision.bind(42L)))
            legacy.close()
            fun handle() = createPostgresDatabase(definitionDatabaseConfiguration(schema, listOf(PgDefinitionV1)), url).also(handles::add)
            val a = handle(); val b = handle()
            coroutineScope { listOf(async { a.open() }, async { b.open() }).awaitAll() }
            val ra = PgRecords(a); val rb = PgRecords(b)
            assertEquals(PgRecord(1, 42), ra.read(1))
            a.transaction("same-record") { ra.put(PgRecord(1, Long.MAX_VALUE)) }
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
        val base = System.getenv("KTSTORE_TEST_PG_URL")
        assumeTrue("KTSTORE_TEST_PG_URL is required for PostgreSQL verification", base != null)
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
}
