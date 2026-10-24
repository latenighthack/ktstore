package com.latenighthack.ktstore

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Observer events over a persistent backend: migrations, reads, writes, deletes and transactions. */
abstract class ObserverConformance {
    abstract fun backend(config: DatabaseConfiguration): LifecycleStoreDelegate

    protected class Recorder(private val throwing: Boolean = false) : StoreObserver {
        // Observers are synchronous, so guard with a non-suspending spin on tryLock.
        private val lock = kotlinx.coroutines.sync.Mutex()
        private fun <T> locked(block: () -> T): T {
            while (!lock.tryLock()) { }
            try { return block() } finally { lock.unlock() }
        }
        private val recorded = mutableListOf<StoreEvent>()
        var onEvent: (StoreEvent) -> Unit = {}
        val events: List<StoreEvent> get() = locked { recorded.toList() }
        fun clear() = locked { recorded.clear() }
        override fun onEvent(event: StoreEvent) {
            locked { recorded.add(event) }
            onEvent.invoke(event)
            if (throwing) throw IllegalStateException("observer failure")
        }
        inline fun <reified T : StoreEvent> single(): T = events.filterIsInstance<T>().single()
    }

    protected class ObservedUsers(database: Database) : Store<example.UserV2>(database, example.UsersV2) {
        suspend fun all() = getAll()
        suspend fun named(name: String) = getAll(example.UsersV2.name.eq(name))
        suspend fun find(id: Int) = get(example.UsersV2.id.eq(id))
        suspend fun many(ids: List<Int>) = getMany(ids.map { example.UsersV2.id.eq(it) })
        suspend fun put(value: example.UserV2) = save(value)
        suspend fun putAll(values: List<example.UserV2>) = saveAll(values)
        suspend fun remove(id: Int) = delete(example.UsersV2.id.eq(id))
        suspend fun removeMany(ids: List<Int>) = deleteMany(ids.map { example.UsersV2.id.eq(it) })
        suspend fun removeAll() = deleteAll()
    }

    private fun StoreOutcome.label() = when (this) {
        StoreOutcome.Completed -> "ok"
        is StoreOutcome.Failed -> "failed"
        is StoreOutcome.Cancelled -> "cancelled"
    }
    private fun migrationTrace(events: List<StoreEvent>): List<String> = events.mapNotNull { event ->
        when (event) {
            is StoreEvent.MigrationStarted -> "started ${event.storedVersion}->${event.targetVersion} ${event.kind}"
            is StoreEvent.MigrationOperation ->
                "op ${event.operation} ${event.store} ${event.fromVersion}->${event.toVersion} rows=${event.rowsTransformed} ${event.outcome.label()}"
            is StoreEvent.MigrationStep -> "step ${event.fromVersion}->${event.toVersion} ${event.outcome.label()}"
            is StoreEvent.MigrationVerification -> "verify ${event.version} ${event.outcome.label()}"
            is StoreEvent.MigrationFinished -> "finished ${event.storedVersion}->${event.targetVersion} ${event.kind} ${event.outcome.label()}" +
                (event.failedFromVersion?.let { " step=$it->${event.failedToVersion} op=${event.failedOperation} ${event.failedStore}" } ?: "")
            else -> null
        }
    }
    private fun operationKind(entry: StoreMigration) = when (entry) {
        is MappedStoreMigration<*, *> -> {
            fun shape(definition: StoreDefinition<*>) = definition.declaration.let { declaration ->
                listOf(declaration.primaryKey.name, declaration.keys.map { Triple(it.name, it::class, it.nullable) }, definition.indexEncodings)
            }
            if (shape(entry.source) == shape(entry.target)) MigrationOperationKind.TRANSFORM else MigrationOperationKind.REBUILD
        }
        is CreateStoreMigration -> MigrationOperationKind.CREATE_STORE
        is RemoveStoreMigration -> MigrationOperationKind.REMOVE_STORE
    }

    private suspend fun observedOpen(config: DatabaseConfiguration, recorder: Recorder = Recorder()): Pair<Database, List<String>> {
        val db = Database(config, backend(config), recorder)
        withContext(CoroutineName("open-${config.identity}")) { db.open() }
        val events = recorder.events
        assertTrue(events.all { it.context[CoroutineName]?.name == "open-${config.identity}" }, "migration events carry the caller's context")
        assertTrue(events.all { it.database == config.identity })
        return db to migrationTrace(events)
    }

    @Test fun migrationEventsCoverFreshCurrentAndEachHistoricalUpgrade() = runTest { withContext(Dispatchers.Default) {
        val history = example.UsersHistory
        val latest = history.catalog.latest
        val migrations = example.generated.generatedStoreMigrations
        // A fresh database at the latest version runs no steps.
        val freshConfig = latest.configuration("observer-fresh-${kotlin.random.Random.nextInt()}", migrations)
        val (fresh, freshTrace) = observedOpen(freshConfig)
        try {
            assertEquals(listOf("started 0->${latest.version} FRESH", "verify ${latest.version} ok", "finished 0->${latest.version} FRESH ok"), freshTrace)
        } finally { fresh.deleteDatabase() }

        history.fixtures.forEachIndexed { index, fixture ->
            val baseline = history.catalog.versions.single { it.version == fixture.baseline }
            val identity = "observer-upgrade-$index-${kotlin.random.Random.nextInt()}"
            val (old, oldTrace) = observedOpen(baseline.configuration(identity))
            assertEquals("started 0->${baseline.version} FRESH", oldTrace.first())
            old.transaction(baseline.stores.map { it.storeName }.toSet()) {
                baseline.stores.forEach { definition ->
                    fixture.oldPayloads.getValue(definition.storeName).forEach { bytes ->
                        @Suppress("UNCHECKED_CAST") val typed = definition as StoreDefinition<Any?>
                        save(definition.storeName, StoreRow(bytes, typed.encodeRow(typed.decode(bytes)).keys))
                    }
                }
            }
            old.close()
            // Reopening the baseline is already current.
            val (current, currentTrace) = observedOpen(baseline.configuration(identity))
            current.close()
            assertEquals(listOf("started ${baseline.version}->${baseline.version} CURRENT", "verify ${baseline.version} ok",
                "finished ${baseline.version}->${baseline.version} CURRENT ok"), currentTrace)

            val (upgraded, trace) = observedOpen(latest.configuration(identity, migrations))
            try {
                val expected = mutableListOf("started ${baseline.version}->${latest.version} UPGRADE")
                history.catalog.transitions.filter { it.source.version >= baseline.version }.forEach { transition ->
                    val from = transition.source.version; val to = transition.target.version
                    transition.entries.forEach { entry ->
                        val store = (entry.source ?: entry.target!!).storeName
                        val kind = operationKind(entry)
                        val rows = if (kind == MigrationOperationKind.TRANSFORM || kind == MigrationOperationKind.REBUILD)
                            (if (from == baseline.version) fixture.oldPayloads.getValue(store).size else null) else null
                        expected += "op $kind ${store.value} $from->$to rows=$rows ok"
                    }
                    expected += "step $from->$to ok"
                }
                expected += "verify ${latest.version} ok"
                expected += "finished ${baseline.version}->${latest.version} UPGRADE ok"
                assertEquals(expected, trace)
                upgraded.transaction(latest.stores.map { it.storeName }.toSet(), TransactionMode.READ_ONLY) {
                    latest.stores.forEach { assertEquals(fixture.expectedRows.getValue(it.storeName).size, getAll(it.storeName).size) }
                }
            } finally { upgraded.deleteDatabase() }
        }
    } }

    @Test fun failingMigrationStepIsNamedAndRethrownUnchanged() = runTest { withContext(Dispatchers.Default) {
        val identity = "observer-failure-${kotlin.random.Random.nextInt()}"
        val baseline = example.UsersHistory.v1.configuration(identity)
        val initial = Database(baseline, backend(baseline))
        initial.open()
        initial.transaction(setOf(example.UsersV1.storeName)) {
            save(example.UsersV1.storeName, example.UsersV1.encodeRow(example.UserV1(1, "Alice")))
        }
        initial.close()
        val step = MigrationTransition(example.UsersHistory.v1, example.UsersHistory.v2,
            listOf(mappedMigration(example.UsersV1, example.UsersV2, { error("conversion failure") })))
        val config = example.UsersHistory.v2.configuration(identity, listOf(step.migration()))
        val recorder = Recorder()
        val failed = Database(config, backend(config), recorder)
        try {
            val thrown = assertFailsWith<StoreFailure.Migration> { failed.open() }
            assertEquals(listOf(
                "started 1->2 UPGRADE",
                "op REBUILD users 1->2 rows=1 failed",
                "step 1->2 failed",
                "finished 1->2 UPGRADE failed step=1->2 op=REBUILD users",
            ), migrationTrace(recorder.events))
            assertSame(thrown, (recorder.single<StoreEvent.MigrationFinished>().outcome as StoreOutcome.Failed).error)
        } finally { failed.close() }
        val old = Database(baseline, backend(baseline))
        try {
            old.open()
            old.transaction(setOf(example.UsersV1.storeName), TransactionMode.READ_ONLY) { assertEquals(1, getAll(example.UsersV1.storeName).size) }
        } finally { old.deleteDatabase() }
    } }

    private suspend fun <T> observedUsers(recorder: StoreObserver, block: suspend (Database, ObservedUsers) -> T): T {
        val config = example.UsersHistory.v2.configuration("observer-ops-${kotlin.random.Random.nextInt()}")
        val db = Database(config, backend(config), ReadCacheConfiguration(), recorder)
        val users = ObservedUsers(db)
        try {
            users.prepare(); db.open()
            return block(db, users)
        } finally { db.deleteDatabase() }
    }
    private fun user(id: Int) = example.UserV2(id, "User$id", true)
    private fun size(id: Int) = example.encodeUserV2(user(id)).size.toLong()

    @Test fun operationsReportStoresKindsCountsAndOutcomes() = runTest { withContext(Dispatchers.Default) {
        val recorder = Recorder()
        observedUsers(recorder) { db, users ->
            val store = example.UsersV2.storeName
            suspend fun <T> events(block: suspend () -> T): List<StoreEvent> {
                recorder.clear()
                withContext(CoroutineName("caller")) { block() }
                return recorder.events.also { events -> assertTrue(events.all { it.context[CoroutineName]?.name == "caller" }) }
            }

            events { users.put(user(1)) }.single().let { it as StoreEvent.Write
                assertEquals(WriteOperation.SAVE, it.operation); assertEquals("users", it.store)
                assertEquals(1, it.rows); assertEquals(size(1), it.bytes); assertFalse(it.inTransaction); assertSame(StoreOutcome.Completed, it.outcome)
            }
            events { users.putAll((2..5).map(::user)) }.single().let { it as StoreEvent.Write
                assertEquals(WriteOperation.SAVE_ALL, it.operation); assertEquals(4, it.rows); assertEquals((2..5).sumOf(::size), it.bytes)
            }
            events { assertEquals(5, users.all().size) }.single().let { it as StoreEvent.Read
                assertEquals(ReadOperation.GET_ALL, it.operation); assertEquals(RelationKind.ALL, it.relation); assertNull(it.index)
                assertEquals(5, it.rows); assertEquals(5, it.fetchedRows); assertEquals(0, it.cachedRows); assertEquals((1..5).sumOf(::size), it.bytes)
            }
            // getAll warmed primary keys, so this is served by the shared cache.
            events { assertEquals(user(1), users.find(1)) }.single().let { it as StoreEvent.Read
                assertEquals(ReadOperation.GET, it.operation); assertEquals(RelationKind.KEY, it.relation); assertEquals("id", it.index)
                assertEquals(1, it.rows); assertEquals(1, it.cachedRows); assertEquals(0, it.fetchedRows)
                assertEquals("id", it.relations.single().key.name, "bound keys are only reachable by reference")
            }
            // A miss loads through a batch, reported once for the batch and once for the call.
            events { assertNull(users.find(42)) }.let { events ->
                assertEquals(listOf(ReadOperation.GET_MANY_GROUPED, ReadOperation.GET), events.map { (it as StoreEvent.Read).operation })
                assertEquals(1, (events[0] as StoreEvent.Read).relations.size)
                assertEquals(0, (events[1] as StoreEvent.Read).rows)
            }
            events { assertEquals(listOf(user(1), user(2)), users.many(listOf(1, 2, 42))) }.single().let { it as StoreEvent.Read
                assertEquals(ReadOperation.GET_MANY, it.operation); assertEquals(RelationKind.KEY, it.relation); assertEquals(3, it.relations.size)
                assertEquals(2, it.rows); assertEquals(2, it.cachedRows)
            }
            events { assertEquals(listOf(user(3)), users.named("User3")) }.single().let { it as StoreEvent.Read
                assertEquals(RelationKind.INDEX, it.relation); assertEquals("name", it.index); assertEquals(1, it.fetchedRows)
            }
            events { users.remove(5) }.single().let { it as StoreEvent.Delete
                assertEquals(DeleteOperation.DELETE, it.operation); assertEquals(setOf("users"), it.stores)
                assertEquals(RelationKind.KEY, it.relation); assertEquals("id", it.index); assertNull(it.rowsRemoved); assertFalse(it.inTransaction)
            }
            events { users.removeMany(listOf(4, 3)) }.single().let { it as StoreEvent.Delete
                assertEquals(DeleteOperation.DELETE_MANY, it.operation); assertEquals(2, it.relations.size)
            }
            events { db.transaction("observer-lock") { users.put(user(6)) } }.let { events ->
                assertTrue((events[0] as StoreEvent.Write).inTransaction)
                val tx = events[1] as StoreEvent.Transaction
                assertEquals("observer-lock", tx.lockKey); assertTrue(tx.committed); assertEquals(2, events.size)
            }
            events { db.transaction(setOf(store)) { save(users, user(7)); assertEquals(user(7), get(users, example.UsersV2.id.eq(7))) } }.let { events ->
                assertTrue((events[0] as StoreEvent.Write).inTransaction)
                (events[1] as StoreEvent.Read).let { assertTrue(it.inTransaction); assertEquals(1, it.fetchedRows); assertEquals(RelationKind.KEY, it.relation) }
                (events[2] as StoreEvent.Transaction).let { assertNull(it.lockKey); assertEquals(setOf("users"), it.stores); assertTrue(it.committed) }
            }
            var thrown: Throwable? = null
            events { thrown = assertFails { db.transaction(setOf(store)) { save(users, user(8)); error("rollback") } } }.last().let { it as StoreEvent.Transaction
                assertFalse(it.committed); assertSame(thrown, (it.outcome as StoreOutcome.Failed).error)
            }
            assertNull(users.find(8))
            db.transaction(setOf(store)) {
                save(store, StoreRow("broken".encodeToByteArray(), listOf(example.UsersV2.id.key.bind(9), example.UsersV2.name.key.bind("Bad"), example.UsersV2.enabled.key.bind(true))))
            }
            events { thrown = assertFailsWith<StoreFailure.CorruptRecord> { users.find(9) } }.last().let { it as StoreEvent.Read
                assertEquals("users", it.store); assertSame(thrown, (it.outcome as StoreOutcome.Failed).error)
            }
            events { users.removeAll() }.single().let { it as StoreEvent.Delete
                assertEquals(DeleteOperation.DELETE_ALL, it.operation); assertEquals(RelationKind.ALL, it.relation)
            }
            events { db.clearStores(setOf(store)) }.let { events ->
                assertEquals(2, events.size, "clearing stores reports one delete, not one per store")
                assertTrue((events[0] as StoreEvent.Transaction).committed)
                (events[1] as StoreEvent.Delete).let { assertEquals(DeleteOperation.CLEAR_STORES, it.operation); assertEquals(setOf("users"), it.stores) }
            }
        }
    } }

    @Test fun observerFailuresAreSwallowed() = runTest { withContext(Dispatchers.Default) {
        val recorder = Recorder(throwing = true)
        observedUsers(recorder) { db, users ->
            users.put(user(1))
            db.transaction("swallow") { users.putAll(listOf(user(2), user(3))) }
            assertEquals(listOf(user(1), user(2), user(3)), users.all())
            assertEquals(user(2), users.find(2))
            users.remove(3)
            assertNull(users.find(3))
            assertTrue(recorder.events.isNotEmpty())
        }
    } }

    @Test fun cancellationMidTransactionIsReportedAndPropagates() = runTest { withContext(Dispatchers.Default) {
        val recorder = Recorder()
        observedUsers(recorder) { db, users ->
            recorder.clear()
            val store = example.UsersV2.storeName
            val job = launch {
                val current = coroutineContext[Job]!!
                // Cancel from inside the first write's event, before the second write.
                recorder.onEvent = { if (it is StoreEvent.Write) { recorder.onEvent = {}; current.cancel() } }
                db.transaction(setOf(store)) { save(users, user(1)); save(users, user(2)) }
            }
            job.join()
            assertTrue(job.isCancelled)
            val tx = recorder.single<StoreEvent.Transaction>()
            assertIs<StoreOutcome.Cancelled>(tx.outcome); assertFalse(tx.committed)
            assertIs<StoreOutcome.Cancelled>(recorder.events.filterIsInstance<StoreEvent.Write>().last().outcome)
            assertNull(users.find(1), "a cancelled transaction rolls back")
        }
    } }

    @Test fun observedOperationsTakeTheSamePaths() = runTest { withContext(Dispatchers.Default) {
        suspend fun scenario(observer: StoreObserver?): List<Any?> {
            var decodes = 0
            val definition = object : StoreDefinition<example.UserV2>(example.UsersV2.storeName, example.UsersV2.payloadFormat,
                { decodes++; example.decodeUserV2(it) }, { example.encodeUserV2(it) }) {
                val id = integerIndex(IndexName("id"), example.UserV2::id)
                val name = stringIndex(IndexName("name"), example.UserV2::name)
                val enabled = booleanIndex(IndexName("enabled"), example.UserV2::enabled)
                init { primaryKey(id) }
            }
            val config = example.UsersHistory.v2.configuration("observer-paths-${kotlin.random.Random.nextInt()}")
            val counts = CountingDelegate(backend(config))
            val db = Database(config, counts, ReadCacheConfiguration(maxEntriesPerStore = 100, batchSize = 4), observer)
            val users = object : Store<example.UserV2>(db, definition) {
                suspend fun all() = getAll()
                suspend fun find(id: Int) = get(definition.id.eq(id))
                suspend fun many(ids: List<Int>) = getMany(ids.map { definition.id.eq(it) })
            }
            try {
                db.open(); users.prepare()
                db.transaction(setOf(definition.storeName)) { saveAll(users, (1..10).map(::user)) }
                val found = withContext(Dispatchers.Default.limitedParallelism(1)) { (1..11).map { async { users.find(it) } }.awaitAll() }
                decodes = 0
                val rows = users.all()
                val lazy = decodes
                val third = rows[2]
                val many = users.many(listOf(1, 12, 3))
                val inTransaction = db.transaction(setOf(definition.storeName), TransactionMode.READ_ONLY) { getAll(users).size }
                db.transaction("paths") { users.find(4) }
                return listOf(found, lazy, third, decodes, many, inTransaction,
                    counts.gets, counts.allReads, counts.groupedBatches.toList(), counts.batches.toList())
            } finally { db.deleteDatabase() }
        }
        val recorder = Recorder()
        assertEquals(scenario(null), scenario(recorder))
        assertTrue(recorder.events.any { it is StoreEvent.Read && it.operation == ReadOperation.GET_MANY_GROUPED })
    } }
}
