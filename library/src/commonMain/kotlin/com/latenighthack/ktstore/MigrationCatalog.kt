package com.latenighthack.ktstore

/** Definitions must retain the codecs used to write that historical version. */
class DatabaseVersion(val version: Int, stores: List<StoreDefinition<*>>, val reference: String = "") {
    private val definitions = stores.toList()
    val stores: List<StoreDefinition<*>> get() = definitions.toList()
    val declarations: List<StoreDeclaration> get() = definitions.map { it.declaration }
    init {
        require(version > 0)
        DatabaseConfiguration("catalog", version, declarations)
    }
    fun configuration(identity: String, migrations: List<DatabaseMigration> = emptyList(), legacyVersion: Int? = null) =
        DatabaseConfiguration(identity, version, declarations, migrations, legacyVersion)
}

sealed class StoreMigration {
    abstract val source: StoreDefinition<*>?
    abstract val target: StoreDefinition<*>?
    /** Qualified reference to this entry, used by the source generator. */
    abstract val reference: String
    internal abstract val operation: String
}
class MappedStoreMigration<A, B> internal constructor(
    override val source: StoreDefinition<A>, override val target: StoreDefinition<B>,
    val mapping: (A) -> B, override val reference: String,
) : StoreMigration() {
    override val operation get() = if (source.declaration.signature() == target.declaration.signature() && source.indexEncodings == target.indexEncodings) "transform" else "rebuild"

}
fun <A, B> mappedMigration(source: StoreDefinition<A>, target: StoreDefinition<B>, mapping: (A) -> B, reference: String = "") =
    MappedStoreMigration(source, target, mapping, reference)
class CreateStoreMigration(override val target: StoreDefinition<*>, override val reference: String = "") : StoreMigration() {
    override val source: StoreDefinition<*>? get() = null
    override val operation get() = "create"
}
class RemoveStoreMigration(override val source: StoreDefinition<*>, override val reference: String = "") : StoreMigration() {
    override val target: StoreDefinition<*>? get() = null
    override val operation get() = "remove"
}
class MigrationTransition(val source: DatabaseVersion, val target: DatabaseVersion, entries: List<StoreMigration>) {
    private val steps = entries.sortedBy { it.source?.storeName?.value ?: it.target!!.storeName.value }.toList()
    val entries get() = steps.toList()
    init {
        require(target.version == source.version + 1)
        val old = source.stores.associateBy { it.storeName }
        val new = target.stores.associateBy { it.storeName }
        require(steps.mapNotNull { it.source?.storeName }.distinct().size == steps.count { it.source != null })
        require(steps.mapNotNull { it.target?.storeName }.distinct().size == steps.count { it.target != null })
        steps.forEach { step ->
            step.source?.let { require(old[it.storeName] === it) { "Mapping source is not the version definition" } }
            step.target?.let { require(new[it.storeName] === it) { "Mapping target is not the version definition" } }
            if (step.source != null && step.target != null && step.source!!.storeName != step.target!!.storeName)
                require(step.target!!.storeName !in old) { "Rename would overwrite another source" }
            if (step.source == null) require(step.target!!.storeName !in old)
            if (step.target == null) require(step.source!!.storeName !in new)
        }
        old.forEach { (name, definition) ->
            if (steps.none { it.source?.storeName == name }) {
                require(steps.none { it.target?.storeName == name }) { "Target would overwrite an unchanged source" }
                require(new[name]?.manifest() == definition.manifest()) { "Changed or removed store needs an explicit migration" }
            }
        }
        new.keys.forEach { name -> require(name in old || steps.any { it.target?.storeName == name }) { "New store needs an explicit creation" } }
    }
    fun migration(): DatabaseMigration = DatabaseMigration.configured(source.version, target.version, source.declarations, target.declarations) {
        // Scope operations preserve the backend transaction and restricted suspension.
        entries.forEach { entry ->
            when (entry) {
                is MappedStoreMigration<*, *> -> applyMapping(entry)
                is CreateStoreMigration -> createStore(entry.target.declaration)
                is RemoveStoreMigration -> removeStore(entry.source.storeName)
            }
        }
    }
}
class MigrationCatalog(versions: List<DatabaseVersion>, transitions: List<MigrationTransition>) {
    private val history = versions.sortedBy { it.version }.toList()
    private val upgrades = transitions.sortedBy { it.source.version }.toList()
    val versions get() = history.toList()
    val transitions get() = upgrades.toList()
    val latest get() = history.last()
    init {
        require(history.isNotEmpty() && history.map { it.version }.distinct().size == history.size)
        require(history.zipWithNext().all { (a, b) -> b.version == a.version + 1 })
        require(upgrades.size == history.size - 1)
        history.zipWithNext().forEach { (a, b) -> require(upgrades.singleOrNull { it.source.version == a.version }?.let { it.source === a && it.target === b } == true) }
    }
    fun migrations(): List<DatabaseMigration> = upgrades.map { it.migration() }
}

/** Stable metadata for tooling, deliberately separate from the persisted schema fingerprint. */
fun StoreDefinition<*>.manifest(): String = listOf(
    declaration.signature(), payloadFormat,
    indexEncodings.entries.sortedBy { it.key }.joinToString("") { "${it.key.length}:${it.key}${it.value.length}:${it.value}" },
).joinToString("") { "${it.length}:$it" }

internal fun <A, B> mappedRows(source: StoreDefinition<A>, target: StoreDefinition<B>, mapping: (A) -> B): (ByteArray) -> StoreRow {
    val seen = mutableSetOf<Any>()
    return { bytes ->
        val row = target.encodeRow(mapping(source.decode(bytes)))
        val primary = row.keys.single { it.name == target.declaration.primaryKey.name }
        // Composite component order and byte contents are part of persisted identity.
        fun value(key: BoundStoreKey): Any = when (key) {
            is BoundStoreKey.SerializedKey -> key.value.copyOf().toComparable()
            is BoundStoreKey.CompositeKey -> key.values.map { value(it) }
            is BoundStoreKey.NullKey -> throw StoreFailure.Migration()
            else -> requireNotNull(key.toAny())
        }
        if (!seen.add(value(primary))) throw StoreFailure.Migration()
        row
    }
}
