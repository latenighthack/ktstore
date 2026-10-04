package com.latenighthack.ktstore

import example.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.io.File
import kotlin.test.*

class LegacyDefinitionMigrationTest {
    @Test fun unversionedSqliteRequiresExplicitBaselineAndPreservesPayload() = runBlocking {
        val directory = Files.createTempDirectory("legacy-defined").toFile()
        val file = File(directory, "users.db")
        val driver = JdbcDriver(file.absolutePath, "sqlite")
        val legacy = SqlStoreDelegate(driver, "BLOB")
        val schema = UsersV1.declaration
        legacy.registerStore(schema.name.value, schema.keys, schema.primaryKey)
        legacy.createStores()
        legacy.save(schema.name.value, "1|Alice".encodeToByteArray(), UsersV1.encodeRow(UserV1(1, "Alice")).keys)
        legacy.close()
        fun database(baseline: Int?): Database {
            val config = UsersHistory.v2.configuration("legacy", UsersHistory.catalog.migrations(), baseline)
            return createDatabase(config, file.absolutePath)
        }
        val missing = database(null)
        try { assertFailsWith<StoreFailure.Migration> { missing.open() } } finally { missing.close() }
        val upgraded = database(1)
        try {
            upgraded.open()
            upgraded.transaction(setOf(UsersV2.storeName), TransactionMode.READ_ONLY) {
                assertContentEquals("1|Alice|true".encodeToByteArray(), get(UsersV2.storeName, UsersV2.id.eq(1)) as ByteArray)
            }
        } finally { upgraded.deleteDatabase(); directory.deleteRecursively() }
    }    @Test fun legacyTransformEstablishesPhysicalOrderingIndices() = runBlocking {
        val directory = Files.createTempDirectory("legacy-transform").toFile()
        val file = File(directory, "users.db")
        val legacy = SqlStoreDelegate(JdbcDriver(file.absolutePath, "sqlite"), "BLOB")
        val schema = UsersV1.declaration
        legacy.registerStore(schema.name.value, schema.keys, schema.primaryKey)
        legacy.createStores()
        legacy.save(schema.name.value, "1|Alice".encodeToByteArray(), UsersV1.encodeRow(UserV1(1, "Alice")).keys)
        legacy.close()
        val target = object : StoreDefinition<UserV1>(UsersV1.storeName, "upper-v1", ::decodeUserV1, ::encodeUserV1) {
            val id = integerIndex(IndexName("id"), UserV1::id)
            val name = stringIndex(IndexName("name"), UserV1::name)
            init { primaryKey(id) }
        }
        val latest = DatabaseVersion(2, listOf(target))
        val step = MigrationTransition(UsersHistory.v1, latest, listOf(mappedMigration(UsersV1, target, { it.copy(name = it.name.uppercase()) })))
        val config = latest.configuration("legacy-order", listOf(step.migration()), legacyVersion = 1)
        val upgraded = createDatabase(config, file.absolutePath)
        try {
            upgraded.open()
            upgraded.transaction(setOf(target.storeName), TransactionMode.READ_ONLY) {
                val result = query(target.storeName, target.id.query(10))
                assertContentEquals("1|ALICE".encodeToByteArray(), result.records.single() as ByteArray)
            }
        } finally { upgraded.deleteDatabase(); directory.deleteRecursively() }
    }

}
