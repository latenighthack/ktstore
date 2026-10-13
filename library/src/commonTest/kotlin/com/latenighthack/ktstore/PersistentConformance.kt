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

    @Test fun definitionAdoptionRejectsBinaryAndCompositeKeyCollisions() = runTest { withContext(Dispatchers.Default) {
        data class Identity(val id: ByteArray, val group: Int)
        for (composite in listOf(false, true)) {
            val definition = object : StoreDefinition<Identity>(StoreName("binary_records"), "binary-v1",
                { Identity(byteArrayOf(it.first()), 7) }, { it.id.copyOf() }) {
                val id = bytesIndex(IndexName("id"), Identity::id, "raw-v1")
                val group = integerIndex(IndexName("group_id"), Identity::group)
                val compound = compositeIndex(IndexName("identity"), id, group)
                init { primaryKey(if (composite) compound else id) }
            }
            val latest = definitionDatabaseConfiguration("adoption-binary-${kotlin.random.Random.nextInt()}", listOf(definition))
            val baseline = latest.copy(version = 2, migrations = emptyList())
            val old = Database(baseline, backend(baseline)); old.open()
            val payloads = listOf(byteArrayOf(1, 9), byteArrayOf(1, 10))
            old.transaction(setOf(definition.storeName)) {
                payloads.forEachIndexed { offset, payload ->
                    val keys = definition.encodeRow(Identity(byteArrayOf((offset + 1).toByte()), 7)).keys
                    save(definition.storeName, StoreRow(payload, keys))
                }
            }
            old.close()
            val failed = Database(latest, backend(latest))
            try { assertFailsWith<StoreFailure.Migration> { failed.open() } } finally { failed.close() }
            val reopened = Database(baseline, backend(baseline))
            try {
                reopened.open()
                reopened.transaction(setOf(definition.storeName), TransactionMode.READ_ONLY) {
                    val rows = getAll(definition.storeName)
                    assertEquals(2, rows.size)
                    payloads.forEach { payload -> assertTrue(rows.any { (it as ByteArray).contentEquals(payload) }) }
                }
            } finally { reopened.deleteDatabase() }
        }
    } }

    @Test fun definitionAdoptionCorruptionRollsBackOptionalStoresAndVersion() = runTest { withContext(Dispatchers.Default) {
        val definition = example.UsersV1
        val optional = object : StoreDefinition<example.UserV1>(StoreName("optional_before_failure"), "user-v1",
            { example.decodeUserV1(it) }, { example.encodeUserV1(it) }) {
            val id = integerIndex(IndexName("id"), example.UserV1::id)
            init { primaryKey(id) }
        }
        // Create the optional store before encountering corruption in the next store.
        val latest = definitionDatabaseConfiguration("adoption-corrupt-${kotlin.random.Random.nextInt()}", listOf(optional, definition))
        val baseline = latest.copy(version = 2, stores = listOf(definition.declaration), migrations = emptyList())
        val old = Database(baseline, backend(baseline)); old.open()
        val payload = "corrupt historical record".encodeToByteArray()
        old.transaction(setOf(definition.storeName)) {
            save(definition.storeName, StoreRow(payload, listOf(definition.id.key.bind(1), definition.name.key.bind("Alice"))))
        }
        old.close()
        val failed = Database(latest, backend(latest))
        try { assertFailsWith<StoreFailure.Migration> { failed.open() } } finally { failed.close() }
        val reopened = Database(baseline, backend(baseline))
        try {
            // Baseline schema validation also proves the optional store was rolled back.
            reopened.open()
            reopened.transaction(setOf(definition.storeName), TransactionMode.READ_ONLY) {
                assertContentEquals(payload, get(definition.storeName, definition.id.eq(1)) as ByteArray)
            }
        } finally { reopened.deleteDatabase() }
    } }

    @Test fun storeGetAllDecodesOnlyAccessedRowsAndCachesThem() = runTest { withContext(Dispatchers.Default) {
        var decodes = 0
        val definition = object : StoreDefinition<example.UserV2>(example.UsersV2.storeName, example.UsersV2.payloadFormat,
            { decodes++; example.decodeUserV2(it) }, { example.encodeUserV2(it) }) {
            val id = integerIndex(IndexName("id"), example.UserV2::id)
            init { primaryKey(id) }
        }
        val config = DatabaseConfiguration("lazy-list-${kotlin.random.Random.nextInt()}", 1, listOf(definition.declaration))
        val counts = CountingDelegate(backend(config))
        val db = Database(config, counts)
        val users = object : Store<example.UserV2>(db, definition) {
            suspend fun all() = getAll()
            suspend fun find(id: Int) = get(definition.id.eq(id))
        }
        try {
            db.open(); users.prepare()
            db.transaction(setOf(definition.storeName)) {
                saveAll(users, (1..100).map { example.UserV2(it, "User$it", true) })
            }
            decodes = 0
            val rows = users.all()
            assertEquals(100, rows.size)
            val slice = rows.subList(50, 60)
            assertEquals(0, decodes, "fetching rows and inspecting size must not decode payloads")
            val value = users.find(51)!!
            assertTrue(counts.batches.isEmpty(), "unaccessed getAll rows already warm primary-key fetches")
            assertSame(value, slice[0])
            assertEquals(51, value.id)
            assertSame(value, rows[50])
            assertSame(value, rows.listIterator(50).next())
            assertEquals(1, decodes, "repeated access and sublists share decoded elements")
            db.close()
            assertEquals(100, rows[99].id, "lazy decoding uses the fetched snapshot after close")
            assertEquals(2, decodes)
        } finally { db.deleteDatabase() }
    } }

    @Test fun storeGetAllDecodesCorruptRowsOnAccess() = runTest { withContext(Dispatchers.Default) {
        val config = example.UsersHistory.v2.configuration("get-all-corrupt-${kotlin.random.Random.nextInt()}")
        val db = Database(config, backend(config))
        val users = UserListStore(db)
        try {
            users.prepare()
            db.open()
            db.transaction(setOf(example.UsersV2.storeName)) {
                save(users, example.UserV2(1, "Alice", true))
                save(example.UsersV2.storeName, StoreRow("broken".encodeToByteArray(), listOf(
                    example.UsersV2.id.key.bind(2), example.UsersV2.name.key.bind("Corrupt"), example.UsersV2.enabled.key.bind(true),
                )))
            }
            val rows = users.all()
            assertEquals(2, rows.size)
            assertEquals(example.UserV2(1, "Alice", true), rows[0])
            assertSame(rows[0], rows[0], "accessed rows retain their decoded value")
            assertFailsWith<StoreFailure.CorruptRecord> { rows[1] }
        } finally { db.deleteDatabase() }
    } }

    private suspend fun <T> cachedUsers(
        cache: ReadCacheConfiguration,
        block: suspend (db: Database, users: UserListStore, counts: CountingDelegate, config: DatabaseConfiguration) -> T,
    ): T {
        val config = example.UsersHistory.v2.configuration("read-cache-${kotlin.random.Random.nextInt()}")
        val counts = CountingDelegate(backend(config))
        val db = Database(config, counts, cache)
        val users = UserListStore(db)
        try {
            users.prepare()
            db.open()
            db.transaction(setOf(example.UsersV2.storeName)) { (1..10).forEach { save(users, example.UserV2(it, "User$it", true)) } }
            return block(db, users, counts, config)
        } finally { db.deleteDatabase() }
    }
    // One thread makes coalescing deterministic: every lookup is queued before the first batch reads.
    private suspend fun findConcurrently(users: UserListStore, ids: List<Int>) = withContext(Dispatchers.Default.limitedParallelism(1)) {
        ids.map { async { users.find(it) } }.awaitAll()
    }

    @Test fun storeGetBatchesConcurrentMissesAndCachesResults() = runTest { withContext(Dispatchers.Default) {
        cachedUsers(ReadCacheConfiguration(maxEntriesPerStore = 100, batchSize = 4)) { _, users, counts, _ ->
            val ids = (1..11).toList()
            val expected = ids.map { id -> if (id <= 10) example.UserV2(id, "User$id", true) else null }
            assertEquals(expected, findConcurrently(users, ids))
            assertEquals(listOf(4, 4, 3), counts.batches)
            assertEquals(0, counts.gets)
            assertEquals(expected, findConcurrently(users, ids))
            assertEquals(expected, ids.map { users.find(it) })
            assertEquals(listOf(4, 4, 3), counts.batches, "hits and cached absences do not reach the delegate")
        }
    } }

    @Test fun storeGetCacheEvictsLeastRecentlyUsedEntries() = runTest { withContext(Dispatchers.Default) {
        cachedUsers(ReadCacheConfiguration(maxEntriesPerStore = 2, batchSize = 8)) { _, users, counts, _ ->
            users.find(1); users.find(2); users.find(1); users.find(3)
            assertEquals(3, counts.batches.size)
            users.find(1)
            assertEquals(3, counts.batches.size)
            users.find(2)
            assertEquals(4, counts.batches.size)
        }
    } }

    @Test fun storeGetCacheIsInvalidatedByEveryWritePath() = runTest { withContext(Dispatchers.Default) {
        cachedUsers(ReadCacheConfiguration()) { db, users, counts, _ ->
            val name = example.UsersV2.storeName
            findConcurrently(users, (1..10).toList())
            fun reads() = counts.batches.size + counts.gets

            users.put(example.UserV2(1, "Store save", true))
            var before = reads()
            assertEquals("Store save", users.find(1)?.name)
            assertEquals("User2", users.find(2)?.name)
            assertEquals(before + 1, reads(), "a save invalidates only its own key")

            db.transaction(setOf(name)) { save(name, example.UsersV2.encodeRow(example.UserV2(2, "Raw row", true))) }
            assertEquals("Raw row", users.find(2)?.name)
            db.transaction(setOf(name)) { save(users, example.UserV2(3, "Scoped save", true)) }
            assertEquals("Scoped save", users.find(3)?.name)
            db.transaction(setOf(name)) { saveAll(users, listOf(example.UserV2(4, "Scoped saveAll", true))) }
            assertEquals("Scoped saveAll", users.find(4)?.name)
            users.remove(5)
            assertNull(users.find(5))
            db.transaction(setOf(name)) { delete(name, example.UsersV2.id.eq(6)) }
            assertNull(users.find(6))
            db.transaction("logical") { users.put(example.UserV2(7, "Logical", true)) }
            assertEquals("Logical", users.find(7)?.name)

            findConcurrently(users, (1..10).toList())
            before = reads()
            db.clearStores(setOf(name))
            assertEquals(List(10) { null }, findConcurrently(users, (1..10).toList()))
            assertTrue(reads() > before)
        }
    } }

    @Test fun storeGetCacheNeverKeepsUncommittedWrites() = runTest { withContext(Dispatchers.Default) {
        cachedUsers(ReadCacheConfiguration()) { db, users, _, _ ->
            val original = example.UserV2(1, "User1", true)
            assertEquals(original, users.find(1))
            assertFailsWith<IllegalStateException> {
                db.transaction("rollback") {
                    users.put(example.UserV2(1, "Uncommitted", true))
                    assertEquals("Uncommitted", users.find(1)?.name, "transactions read their own writes")
                    error("roll back")
                }
            }
            assertEquals(original, users.find(1))
            assertFails {
                db.transaction(setOf(example.UsersV2.storeName)) {
                    save(users, example.UserV2(1, "Uncommitted", true))
                    error("roll back")
                }
            }
            assertEquals(original, users.find(1))
        }
    } }

    @Test fun storeGetCacheIsSharedAcrossHandlesOfOneDatabase() = runTest { withContext(Dispatchers.Default) {
        cachedUsers(ReadCacheConfiguration()) { _, users, _, config ->
            assertEquals("User1", users.find(1)?.name)
            val other = Database(config, backend(config))
            val otherUsers = UserListStore(other)
            try {
                otherUsers.prepare()
                other.open()
                otherUsers.put(example.UserV2(1, "Other handle", true))
                assertEquals("Other handle", users.find(1)?.name)
            } finally { other.close() }
        }
    } }

    @Test fun storeGetBatchIsolatesCorruptRows() = runTest { withContext(Dispatchers.Default) {
        cachedUsers(ReadCacheConfiguration()) { db, users, _, _ ->
            db.transaction(setOf(example.UsersV2.storeName)) {
                save(example.UsersV2.storeName, StoreRow("broken".encodeToByteArray(), listOf(
                    example.UsersV2.id.key.bind(2), example.UsersV2.name.key.bind("Corrupt"), example.UsersV2.enabled.key.bind(true),
                )))
            }
            val results = withContext(Dispatchers.Default.limitedParallelism(1)) {
                listOf(1, 2, 3).map { async { runCatching { users.find(it) } } }.awaitAll()
            }
            assertEquals("User1", results[0].getOrThrow()?.name)
            assertIs<StoreFailure.CorruptRecord>(results[1].exceptionOrNull())
            assertEquals("User3", results[2].getOrThrow()?.name)
        }
    } }

    @Test fun allReadPathsShareDecodedObjectsAndWarmPrimaryKeys() = runTest { withContext(Dispatchers.Default) {
        cachedUsers(ReadCacheConfiguration()) { _, users, counts, _ ->
            val all = users.all()
            val next = users.all()
            val first = all[0]
            assertSame(first, next[0])
            assertSame(first, users.named("User1").single())
            assertSame(first, users.oneNamed("User1"))
            val secondaryMany = users.namedMany(listOf("User1", "No match", "User1"))
            assertEquals(2, secondaryMany.size)
            assertSame(first, secondaryMany[0]); assertSame(first, secondaryMany[1])
            val before = counts.batches.size + counts.gets
            assertSame(first, users.find(1))
            assertEquals(before, counts.batches.size + counts.gets, "decoding a list row warms primary-key reads")
            val rawReads = counts.allReads
            assertSame(first, users.byId(1).single())
            assertEquals(rawReads, counts.allReads, "primary-key getAll reuses a valid fetch")
            val beforeGroups = counts.groupedBatches.size
            val many = users.many(listOf(1, 3, 99, 3, 2))
            assertEquals(listOf(1), counts.groupedBatches.drop(beforeGroups), "only the unknown key 99 is fetched; getAll warmed all existing keys")
            assertSame(first, many[0])
            assertSame(many[1], many[2], "duplicate relations share one decoded object")
            assertEquals(listOf(1, 3, 3, 2), many.map { it.id })
            val batches = counts.groupedBatches.size
            val repeated = users.many(listOf(2, 99, 1, 3))
            assertSame(many[3], repeated[0])
            assertSame(first, repeated[1])
            assertSame(many[1], repeated[2])
            assertEquals(batches, counts.groupedBatches.size, "cached rows and absences avoid another fetch")
            val mixed = users.mixed(1, "User2")
            assertSame(first, mixed[0]); assertSame(many[3], mixed[1])
            assertEquals(batches + 1, counts.groupedBatches.size)
            assertEquals(1, counts.groupedBatches.last(), "mixed reads fetch only the secondary query, not the cached primary key")
        }
    } }

    @Test fun decodedObjectsAreSharedAcrossStoresAndOpenHandles() = runTest { withContext(Dispatchers.Default) {
        cachedUsers(ReadCacheConfiguration()) { db, users, _, config ->
            val otherDb = Database(config, backend(config))
            val other = UserListStore(otherDb)
            val sameHandle = UserListStore(db)
            try {
                otherDb.open(); other.prepare(); sameHandle.prepare()
                val first = users.all().first()
                assertSame(first, other.all().first())
                assertSame(first, other.find(1))
                assertSame(first, sameHandle.many(listOf(1)).single())
                other.put(example.UserV2(1, "Changed through other handle", true))
                val changed = other.find(1)!!
                assertNotSame(first, changed)
                assertSame(changed, users.find(1))
                assertSame(changed, sameHandle.all().first())
            } finally { otherDb.close() }
        }
    } }

    @Test fun manyFetchesAreBoundedAndStayLazy() = runTest { withContext(Dispatchers.Default) {
        var decodes = 0
        val definition = object : StoreDefinition<example.UserV2>(StoreName("lazy_many"), "user-v2",
            { decodes++; example.decodeUserV2(it) }, { example.encodeUserV2(it) }) {
            val id = integerIndex(IndexName("id"), example.UserV2::id)
            init { primaryKey(id) }
        }
        val config = DatabaseConfiguration("many-lazy-${kotlin.random.Random.nextInt()}", 1, listOf(definition.declaration))
        val counts = CountingDelegate(backend(config))
        val db = Database(config, counts, ReadCacheConfiguration(batchSize = 4))
        val users = object : Store<example.UserV2>(db, definition) {
            suspend fun many(ids: List<Int>) = getMany(ids.map { definition.id.eq(it) })
            suspend fun all() = getAll()
            suspend fun find(id: Int) = get(definition.id.eq(id))
        }
        try {
            db.open(); users.prepare()
            db.transaction(setOf(definition.storeName)) { saveAll(users, (1..10).map { example.UserV2(it, "User$it", true) }) }
            decodes = 0
            val rows = users.many((1..11).toList())
            assertEquals(listOf(4, 4, 3), counts.groupedBatches)
            assertEquals(10, rows.size)
            assertEquals(0, decodes)
            val all = users.all()
            assertEquals(0, decodes)
            val first = rows[0]
            assertSame(first, all[0])
            assertSame(first, users.find(1))
            assertEquals(1, decodes)
        } finally { db.deleteDatabase() }
    } }

    @Test fun oldListsCannotResurrectInvalidatedRowsAndEvictionDropsDecodedObjects() = runTest { withContext(Dispatchers.Default) {
        cachedUsers(ReadCacheConfiguration(maxEntriesPerStore = 2)) { _, users, counts, _ ->
            val old = users.byId(1)
            val original = old.single()
            assertSame(original, users.find(1))
            users.put(example.UserV2(1, "Changed", true))
            val changed = users.find(1)!!
            assertEquals("User1", old.single().name, "old lists remain read snapshots")
            assertSame(changed, users.find(1), "old access cannot overwrite the updated cache")
            users.find(2); users.find(3)
            val before = counts.batches.size
            val fetched = users.find(1)!!
            assertNotSame(changed, fetched, "eviction includes decoded objects")
            assertEquals(before + 1, counts.batches.size)
            assertSame(fetched, users.byId(1).single())
        }
    } }

    @Test fun manyRefetchesWhenAWriteOverlapsCachedHitsAndMisses() = runTest { withContext(Dispatchers.Default) {
        val config = example.UsersHistory.v2.configuration("many-write-race-${kotlin.random.Random.nextInt()}")
        val counts = CountingDelegate(backend(config))
        var beforeRead: (suspend () -> Unit)? = null
        val wrapper = object : LifecycleStoreDelegate by counts, ScopedStoreDelegate {
            override val supportsTransactions get() = counts.supportsTransactions
            override suspend fun <T> transaction(block: suspend () -> T): T = counts.transaction(block)
            override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T = counts.transaction(lockKey, block)
            override suspend fun <T> transaction(stores: Set<String>, mode: TransactionMode, block: suspend () -> T): T {
                if (mode == TransactionMode.READ_ONLY) {
                    val pending = beforeRead; beforeRead = null
                    pending?.invoke()
                }
                return counts.transaction(stores, mode, block)
            }
        }
        val db = Database(config, wrapper)
        val users = UserListStore(db)
        try {
            db.open(); users.prepare()
            users.put(example.UserV2(1, "Before", true)); users.put(example.UserV2(2, "Second", true))
            users.find(1)
            beforeRead = { users.put(example.UserV2(1, "After", true)) }
            val rows = users.many(listOf(1, 2))
            assertEquals(listOf("After", "Second"), rows.map { it.name })
            assertEquals(listOf(1, 1), counts.groupedBatches, "initial key 1 and the key 2 miss each fetch one group")
            assertEquals(listOf(1, 1, 2), counts.batches, "overlapping writes force one complete fresh read")
            assertSame(rows[0], users.find(1))
        } finally { db.deleteDatabase() }
    } }

    @Test fun sharedListsNeverPublishUncommittedOrRolledBackObjects() = runTest { withContext(Dispatchers.Default) {
        cachedUsers(ReadCacheConfiguration()) { db, users, _, _ ->
            val original = users.find(1)
            lateinit var uncommitted: List<example.UserV2>
            assertFailsWith<IllegalStateException> {
                db.transaction("rollback-list") {
                    users.put(example.UserV2(1, "Uncommitted", true))
                    uncommitted = users.byId(1)
                    error("rollback")
                }
            }
            assertEquals("Uncommitted", uncommitted.single().name)
            assertEquals(original, users.find(1))
            assertNotSame(uncommitted.single(), users.find(1))
        }
    } }

    @Test fun storeGetCacheCanBeDisabled() = runTest { withContext(Dispatchers.Default) {
        cachedUsers(ReadCacheConfiguration.Disabled) { _, users, counts, _ ->
            findConcurrently(users, listOf(1, 2, 3))
            users.find(1)
            assertEquals(4, counts.gets)
            assertTrue(counts.batches.isEmpty())
            val first = users.all().first()
            assertNotSame(first, users.all().first(), "disabled caches do not share decoded list objects")
            assertNotSame(first, users.find(1), "disabled primary-key reads do not share decoded list objects")
        }
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
