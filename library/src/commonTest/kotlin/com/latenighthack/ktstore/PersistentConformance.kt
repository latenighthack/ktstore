package com.latenighthack.ktstore

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

abstract class PersistentConformance : DatabaseConformance() {
    @Test fun definitionAdoptionPreservesOriginalPayloadAndReopens() = runTest { withContext(Dispatchers.Default) {
        val identity = "adoption-${kotlin.random.Random.nextInt()}"
        val definition = example.UsersV1
        val latest = definitionDatabaseConfiguration(identity, listOf(definition))
        for (version in listOf(1, 2)) {
            val oldConfig = DatabaseConfiguration("${identity}_$version", version, listOf(definition.declaration))
            val old = Database(oldConfig, backend(oldConfig))
            val payload = "01|Alice".encodeToByteArray()
            old.open()
            old.transaction(setOf(definition.storeName)) {
                save(definition.storeName, StoreRow(payload, listOf(definition.id.key.bind(1), definition.name.key.bind("Alice"))))
            }
            old.close()
            val config = latest.copy(identity = oldConfig.identity)
            var current = Database(config, backend(config))
            try {
                current.open()
                current.transaction(setOf(definition.storeName), TransactionMode.READ_ONLY) {
                    assertContentEquals(payload, get(definition.storeName, definition.id.eq(1)) as ByteArray)
                }
                current.close()
                current = Database(config, backend(config)); current.open()
                current.transaction(setOf(definition.storeName), TransactionMode.READ_ONLY) {
                    assertContentEquals(payload, get(definition.storeName, definition.id.eq(1)) as ByteArray)
                }
            } finally { current.deleteDatabase() }
        }
    } }

    @Test fun definitionAdoptionCreatesPreviouslyUnregisteredStores() = runTest { withContext(Dispatchers.Default) {
        val definition = example.UsersV1
        val extra = object : StoreDefinition<example.UserV1>(StoreName("optional_users"), "user-v1", { example.decodeUserV1(it) }, { example.encodeUserV1(it) }) {
            val id = integerIndex(IndexName("id"), example.UserV1::id)
            init { primaryKey(id) }
        }
        val config = definitionDatabaseConfiguration("adoption-optional-${kotlin.random.Random.nextInt()}", listOf(definition, extra))
        val oldConfig = config.copy(version = 2, stores = listOf(definition.declaration), migrations = emptyList())
        val old = Database(oldConfig, backend(oldConfig)); old.open()
        old.transaction(setOf(definition.storeName)) { save(definition.storeName, definition.encodeRow(example.UserV1(1, "Alice"))) }
        old.close()
        val current = Database(config, backend(config))
        try {
            current.open()
            current.transaction(setOf(definition.storeName, extra.storeName)) {
                assertEquals(1, getAll(definition.storeName).size)
                assertTrue(getAll(extra.storeName).isEmpty())
                save(extra.storeName, extra.encodeRow(example.UserV1(2, "Bob")))
            }
        } finally { current.deleteDatabase() }
    } }

    @Test fun definitionAdoptionRejectsDuplicateDerivedKeysWithoutLosingOldRows() = runTest { withContext(Dispatchers.Default) {
        val definition = example.UsersV1
        val config = definitionDatabaseConfiguration("adoption-collision-${kotlin.random.Random.nextInt()}", listOf(definition))
        val baseline = config.copy(version = 2, migrations = emptyList())
        val old = Database(baseline, backend(baseline)); old.open()
        old.transaction(setOf(definition.storeName)) {
            save(definition.storeName, StoreRow("1|Alice".encodeToByteArray(), listOf(definition.id.key.bind(1), definition.name.key.bind("Alice"))))
            save(definition.storeName, StoreRow("1|Bob".encodeToByteArray(), listOf(definition.id.key.bind(2), definition.name.key.bind("Bob"))))
        }
        old.close()
        val failed = Database(config, backend(config))
        try { assertFailsWith<StoreFailure.Migration> { failed.open() } } finally { failed.close() }
        val reopened = Database(baseline, backend(baseline))
        try {
            reopened.open()
            reopened.transaction(setOf(definition.storeName), TransactionMode.READ_ONLY) {
                assertEquals(2, getAll(definition.storeName).size)
                assertContentEquals("1|Bob".encodeToByteArray(), get(definition.storeName, definition.id.eq(2)) as ByteArray)
            }
        } finally { reopened.deleteDatabase() }
    } }

    @Test fun generatedMigrationsVerifyHistoricalFixtures() = runTest { withContext(Dispatchers.Default) {
        example.generated.verifyGeneratedStoreMigrations(::backend, "generated-${kotlin.random.Random.nextInt()}")
    } }

    @Test fun typedMigrationCollisionAndCorruptionRollBack() = runTest { withContext(Dispatchers.Default) {
        val identity = "typed-failure-${kotlin.random.Random.nextInt()}"
        val baseline = example.UsersHistory.v1.configuration(identity)
        val initial = Database(baseline, backend(baseline))
        initial.open()
        initial.transaction(setOf(example.UsersV1.storeName)) {
            save(example.UsersV1.storeName, example.UsersV1.encodeRow(example.UserV1(1, "Alice")))
            save(example.UsersV1.storeName, example.UsersV1.encodeRow(example.UserV1(2, "Bob")))
        }
        initial.close()
        val badMappings = listOf<(example.UserV1) -> example.UserV2>(
            { example.UserV2(1, it.name, true) },
            { error("conversion failure") },
        )
        badMappings.forEach { mapping ->
            val entry = mappedMigration(example.UsersV1, example.UsersV2, mapping)
            val step = MigrationTransition(example.UsersHistory.v1, example.UsersHistory.v2, listOf(entry))
            val config = example.UsersHistory.v2.configuration(identity, listOf(step.migration()))
            val failed = Database(config, backend(config))
            try { assertFailsWith<StoreFailure.Migration> { failed.open() } } finally { failed.close() }
            val old = Database(baseline, backend(baseline))
            try {
                old.open()
                old.transaction(setOf(example.UsersV1.storeName), TransactionMode.READ_ONLY) {
                    assertContentEquals("1|Alice".encodeToByteArray(), get(example.UsersV1.storeName, example.UsersV1.id.eq(1)) as ByteArray)
                    assertEquals(2, getAll(example.UsersV1.storeName).size)
                }
            } finally { old.close() }
        }
        val old = Database(baseline, backend(baseline))
        old.open()
        old.transaction(setOf(example.UsersV1.storeName)) {
            save(example.UsersV1.storeName, StoreRow("broken".encodeToByteArray(), listOf(example.UsersV1.id.key.bind(3), example.UsersV1.name.key.bind("Corrupt"))))
        }
        old.close()
        val config = example.UsersHistory.v2.configuration(identity, example.UsersHistory.catalog.migrations())
        val failed = Database(config, backend(config))
        try { assertFailsWith<StoreFailure.Migration> { failed.open() } } finally { failed.close() }
        val recovered = Database(baseline, backend(baseline))
        try {
            recovered.open()
            recovered.transaction(setOf(example.UsersV1.storeName)) {
                assertEquals(3, getAll(example.UsersV1.storeName).size)
                delete(example.UsersV1.storeName, example.UsersV1.id.eq(3))
            }
        } finally { recovered.close() }
        val good = Database(config, backend(config))
        try {
            good.open()
            good.transaction(setOf(example.UsersV2.storeName), TransactionMode.READ_ONLY) {
                assertContentEquals("1|Alice|true".encodeToByteArray(), get(example.UsersV2.storeName, example.UsersV2.id.eq(1)) as ByteArray)
            }
        } finally { good.deleteDatabase() }
    } }

    @Test fun typedMultiStepMigrationUsesIntermediateDefinitions() = runTest { withContext(Dispatchers.Default) {
        val v3Store = object : StoreDefinition<example.UserV2>(example.UsersV2.storeName, "user-v3", { example.decodeUserV2(it) }, { example.encodeUserV2(it) }) {
            val id = integerIndex(IndexName("id"), example.UserV2::id)
            val name = stringIndex(IndexName("name"), example.UserV2::name)
            val enabled = booleanIndex(IndexName("enabled"), example.UserV2::enabled)
            init { primaryKey(id) }
        }
        val v3 = DatabaseVersion(3, listOf(v3Store))
        val second = MigrationTransition(example.UsersHistory.v2, v3, listOf(mappedMigration(example.UsersV2, v3Store, { it.copy(name = it.name.uppercase()) })))
        val catalog = MigrationCatalog(example.UsersHistory.catalog.versions + v3, example.UsersHistory.catalog.transitions + second)
        val identity = "multi-${kotlin.random.Random.nextInt()}"
        val baseline = example.UsersHistory.v1.configuration(identity)
        val initial = Database(baseline, backend(baseline))
        initial.open()
        initial.transaction(setOf(example.UsersV1.storeName)) { save(example.UsersV1.storeName, example.UsersV1.encodeRow(example.UserV1(1, "Alice"))) }
        initial.close()
        val config = v3.configuration(identity, catalog.migrations())
        val upgraded = Database(config, backend(config))
        try {
            upgraded.open()
            upgraded.transaction(setOf(v3Store.storeName), TransactionMode.READ_ONLY) {
                assertContentEquals("1|ALICE|true".encodeToByteArray(), get(v3Store.storeName, v3Store.id.eq(1)) as ByteArray)
            }
        } finally { upgraded.deleteDatabase() }
    } }

    @Test fun typedMigrationRejectsIncorrectSourceWithoutRunningMapping() = runTest { withContext(Dispatchers.Default) {
        val identity = "source-mismatch-${kotlin.random.Random.nextInt()}"
        val baseline = example.UsersHistory.v1.configuration(identity)
        val original = Database(baseline, backend(baseline))
        original.open()
        original.transaction(setOf(example.UsersV1.storeName)) { save(example.UsersV1.storeName, example.UsersV1.encodeRow(example.UserV1(1, "Alice"))) }
        original.close()
        val wrong = object : StoreDefinition<example.UserV1>(example.UsersV1.storeName, "wrong", { example.decodeUserV1(it) }, { example.encodeUserV1(it) }) {
            val id = integerIndex(IndexName("id"), example.UserV1::id)
            init { primaryKey(id) }
        }
        var called = false
        val migration = DatabaseMigration(1, 2) {
            try { rebuildMappedStore(wrong, example.UsersV2) { called = true; example.upgradeUser(it) } }
            catch (_: Throwable) { /* Database failures remain rollback-only even if caught. */ }
        }
        val config = example.UsersHistory.v2.configuration(identity, listOf(migration))
        val failed = Database(config, backend(config))
        try { assertFailsWith<StoreFailure.Migration> { failed.open() } } finally { failed.close() }
        assertFalse(called)
        val reopened = Database(baseline, backend(baseline))
        try {
            reopened.open()
            reopened.transaction(setOf(example.UsersV1.storeName), TransactionMode.READ_ONLY) { assertEquals(1, getAll(example.UsersV1.storeName).size) }
        } finally { reopened.deleteDatabase() }
    } }

    private data class BinaryIdentity(val id: ByteArray, val group: Int)
    @Test fun typedBinaryCompositeCollisionsComparePersistedValues() = runTest { withContext(Dispatchers.Default) {
        val target = object : StoreDefinition<BinaryIdentity>(example.UsersV1.storeName, "binary-1", { BinaryIdentity(byteArrayOf(1), 2) }, { "constant".encodeToByteArray() }) {
            val id = bytesIndex(IndexName("id"), BinaryIdentity::id, "raw-1")
            val group = integerIndex(IndexName("group_id"), BinaryIdentity::group)
            val compound = compositeIndex(IndexName("identity"), id, group)
            init { primaryKey(compound) }
        }
        val latest = DatabaseVersion(2, listOf(target))
        val step = MigrationTransition(example.UsersHistory.v1, latest, listOf(mappedMigration(example.UsersV1, target, { BinaryIdentity(byteArrayOf(1), 2) })))
        val identity = "binary-collision-${kotlin.random.Random.nextInt()}"
        val oldConfig = example.UsersHistory.v1.configuration(identity)
        val old = Database(oldConfig, backend(oldConfig))
        old.open()
        old.transaction(setOf(example.UsersV1.storeName)) {
            save(example.UsersV1.storeName, example.UsersV1.encodeRow(example.UserV1(1, "Alice")))
            save(example.UsersV1.storeName, example.UsersV1.encodeRow(example.UserV1(2, "Bob")))
        }
        old.close()
        val config = latest.configuration(identity, listOf(step.migration()))
        val failed = Database(config, backend(config))
        try { assertFailsWith<StoreFailure.Migration> { failed.open() } } finally { failed.close() }
        val reopened = Database(oldConfig, backend(oldConfig))
        try {
            reopened.open()
            reopened.transaction(setOf(example.UsersV1.storeName), TransactionMode.READ_ONLY) { assertEquals(2, getAll(example.UsersV1.storeName).size) }
        } finally { reopened.deleteDatabase() }
    } }

    @Test fun typedCreateAndRemoveVerifyEmptyDatabaseSchemas() = runTest { withContext(Dispatchers.Default) {
        val empty = DatabaseVersion(1, emptyList())
        val populatedSchema = DatabaseVersion(2, listOf(example.UsersV2))
        val creation = MigrationCatalog(listOf(empty, populatedSchema), listOf(MigrationTransition(empty, populatedSchema, listOf(CreateStoreMigration(example.UsersV2)))))
        MigrationVerifier(creation, listOf(MigrationFixture("empty-baseline", 1, emptyMap(), mapOf(example.UsersV2.storeName to emptyList()))))
            .verify(::backend, "create-${kotlin.random.Random.nextInt()}")
        val removed = DatabaseVersion(2, emptyList())
        val deletion = MigrationCatalog(listOf(example.UsersHistory.v1, removed), listOf(MigrationTransition(example.UsersHistory.v1, removed, listOf(RemoveStoreMigration(example.UsersV1)))))
        MigrationVerifier(deletion, listOf(MigrationFixture("removed-users", 1, mapOf(example.UsersV1.storeName to listOf("1|Alice".encodeToByteArray())), emptyMap())))
            .verify(::backend, "remove-${kotlin.random.Random.nextInt()}")
    } }

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
