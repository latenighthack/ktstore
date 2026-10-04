package com.latenighthack.ktstore

/** Expected rows are authored independently of the mapping under test. */
class MigrationFixture(
    val name: String,
    val baseline: Int,
    oldPayloads: Map<StoreName, List<ByteArray>>,
    expectedRows: Map<StoreName, List<StoreRow>>,
    val assertions: suspend TransactionScope.() -> Unit = {},
) {
    private val old = oldPayloads.mapValues { (_, rows) -> rows.map { it.copyOf() } }
    private val expected = expectedRows.mapValues { (_, rows) -> rows.map { row ->
        require(row.data is ByteArray)
        StoreRow(row.data.copyOf(), copyKeys(row.keys))
    } }
    val oldPayloads get() = old.mapValues { (_, rows) -> rows.map { it.copyOf() } }
    val expectedRows get() = expected.mapValues { (_, rows) -> rows.map { StoreRow((it.data as ByteArray).copyOf(), copyKeys(it.keys)) } }
    init { require(name.isNotBlank()) }
}
interface MigrationSpecification {
    val catalog: MigrationCatalog
    val fixtures: List<MigrationFixture>
}

/** Runs against a real persistent backend. Memory is not migration evidence. */
class MigrationVerifier(private val catalog: MigrationCatalog, private val fixtures: List<MigrationFixture>) {
    fun validateFixtures() {
        require(fixtures.map { it.name }.distinct().size == fixtures.size)
        val baselines = catalog.versions.dropLast(1).map { it.version }.toSet()
        require(fixtures.map { it.baseline }.toSet() == baselines) { "Every historical baseline requires independent fixtures" }
        fixtures.forEach { fixture ->
            val source = catalog.versions.single { it.version == fixture.baseline }
            require(fixture.oldPayloads.keys == source.stores.map { it.storeName }.toSet())
            require(source.stores.isEmpty() || fixture.oldPayloads.values.any { it.isNotEmpty() }) { "Populated historical fixtures are required" }
            require(fixture.expectedRows.keys == catalog.latest.stores.map { it.storeName }.toSet())
        }
    }
    suspend fun verify(backend: (DatabaseConfiguration) -> LifecycleStoreDelegate, identity: String, migrations: List<DatabaseMigration> = catalog.migrations()) {
        validateFixtures()
        fixtures.forEachIndexed { index, fixture ->
            val baseline = catalog.versions.single { it.version == fixture.baseline }
            val oldConfig = baseline.configuration("${identity}_$index")
            val old = Database(oldConfig, backend(oldConfig))
            try {
                old.open()
                if (baseline.stores.isNotEmpty()) old.transaction(baseline.stores.map { it.storeName }.toSet()) {
                    baseline.stores.forEach { definition ->
                        fixture.oldPayloads.getValue(definition.storeName).forEach { bytes ->
                            @Suppress("UNCHECKED_CAST") val typed = definition as StoreDefinition<Any?>
                            val row = typed.encodeRow(typed.decode(bytes))
                            save(definition.storeName, StoreRow(bytes, row.keys))
                        }
                    }
                }
            } catch (error: Throwable) { old.deleteDatabase(); throw error }
            finally { old.close() }
            val config = catalog.latest.configuration(oldConfig.identity, migrations)
            val upgraded = Database(config, backend(config))
            try {
                upgraded.open()
                if (catalog.latest.stores.isNotEmpty()) upgraded.transaction(catalog.latest.stores.map { it.storeName }.toSet(), TransactionMode.READ_ONLY) {
                    catalog.latest.stores.forEach { definition ->
                        val expected = fixture.expectedRows.getValue(definition.storeName)
                        check(getAll(definition.storeName).size == expected.size) { "Migrated row count differs from fixture" }
                        expected.forEach { row ->
                            val keys = normalizeKeys(definition.declaration.keys, row.keys)
                            val key = keys.single { it.name == definition.declaration.primaryKey.name }
                            val actual = get(definition.storeName, StoreRelation.Eq(key)) as? ByteArray
                            check(actual != null && actual.contentEquals(row.data as ByteArray)) { "Migrated row differs from fixture" }
                        }
                    }
                }
                if (catalog.latest.stores.isNotEmpty()) upgraded.transaction(catalog.latest.stores.map { it.storeName }.toSet(), TransactionMode.READ_ONLY, fixture.assertions)
            } finally { upgraded.deleteDatabase() }
        }
        val freshConfig = catalog.latest.configuration("${identity}_fresh", migrations)
        val fresh = Database(freshConfig, backend(freshConfig))
        try {
            fresh.open()
            if (catalog.latest.stores.isNotEmpty()) fresh.transaction(catalog.latest.stores.map { it.storeName }.toSet(), TransactionMode.READ_ONLY) {
                catalog.latest.stores.forEach { check(getAll(it.storeName).isEmpty()) }
            }
        } finally { fresh.deleteDatabase() }
    }
}
