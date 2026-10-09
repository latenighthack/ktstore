package com.latenighthack.ktstore

/**
 * Adopts the pre-configuration schema without changing payload bytes or persisted index names.
 * Browser legacy databases are version 1, Android version 2, JDBC/Apple unversioned.
 * Version 3 rebuilds keys from payloads, including Android's blob-literal TEXT bindings.
 * The supplied V1 definitions are historical contracts: retain their codecs and encodings.
 * This helper is only for initial adoption. Later changes require explicit application migrations.
 */
fun definitionDatabaseConfiguration(
    identity: String,
    definitions: List<StoreDefinition<*>>,
): DatabaseConfiguration {
    require(definitions.isNotEmpty())
    val historical = definitions.toList()
    val declarations = historical.map { it.declaration }
    return DatabaseConfiguration(
        identity = identity, version = 3, stores = declarations, legacyVersion = 2,
        migrations = listOf(
            DatabaseMigration(1, 2) {},
            DatabaseMigration(2, 3) {
                for (definition in historical) {
                    val declaration = definition.declaration
                    // Legacy applications registered optional domain stores lazily.
                    if (!storeExists(definition.storeName)) {
                        createStore(declaration)
                        continue
                    }
                    val seen = mutableSetOf<List<Any?>>()
                    rebuildStore(definition.storeName, declaration) { bytes ->
                        @Suppress("UNCHECKED_CAST")
                        val typed = definition as StoreDefinition<Any>
                        val row = typed.encodeRow(typed.decode(bytes))
                        val primary = declaration.primaryKey
                        val names = if (primary is StoreKey.CompositeKey) primary.names else listOf(primary.name)
                        val key = names.map { name -> row.keys.single { it.name == name }.toAny() }
                        if (!seen.add(key)) throw StoreFailure.Migration()
                        // Index adoption must not erase unknown protobuf fields.
                        StoreRow(bytes.copyOf(), row.keys)
                    }
                }
            },
        ),
    )
}
