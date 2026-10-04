package com.latenighthack.ktstore

import androidx.test.platform.app.InstrumentationRegistry
import example.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class LegacyDefinitionMigrationTest {
    @Test fun explicitlyRebuildsLegacyBlobLiteralTextKeys() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "legacy-blob-${kotlin.random.Random.nextInt()}"
        val codec = object : StorageCodec<String, ByteArray> {
            override fun encode(value: String) = value.encodeToByteArray()
            override fun key(name: IndexName) = StoreKey.SerializedKey(name.value)
        }
        fun definition(encoding: String) = object : StoreDefinition<UserV1>(StoreName("users"), "user-v1", ::decodeUserV1, ::encodeUserV1) {
            val id = integerIndex(IndexName("id"), UserV1::id)
            val name = mappedIndex(IndexName("name"), UserV1::name, codec, encoding)
            init { primaryKey(id) }
        }
        val source = definition("android-blob-literal-text-v1")
        val target = definition("utf8-v1")
        val legacy = SqliteStoreDelegate(context, identity)
        val schema = source.declaration
        legacy.registerStore(schema.name.value, schema.keys, schema.primaryKey)
        legacy.createStores()
        val row = source.encodeRow(UserV1(1, "Alice"))
        legacy.save(schema.name.value, row.data, row.keys)
        legacy.close()
        val file = context.getDatabasePath(identity)
        // The historical Android version is app-owned; establish its explicit baseline.
        android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, 0).use { it.version = 2 }
        val old = DatabaseVersion(2, listOf(source))
        val latest = DatabaseVersion(3, listOf(target))
        val transition = MigrationTransition(old, latest, listOf(mappedMigration(source, target, { it })))
        val config = latest.configuration(identity, listOf(transition.migration()))
        val upgraded = createDatabase(config, file.absolutePath)
        try {
            upgraded.open()
            upgraded.transaction(setOf(target.storeName), TransactionMode.READ_ONLY) {
                assertContentEquals("1|Alice".encodeToByteArray(), get(target.storeName, target.name.eq("Alice")) as ByteArray)
            }
        } finally { upgraded.deleteDatabase() }
    }
}
