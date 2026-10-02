package com.latenighthack.ktstore

/** Validate at the storage boundary and derive compound keys from their scalar components. */
internal fun normalizeKeys(declarations: List<StoreKey<*>>, values: List<BoundStoreKey>): List<BoundStoreKey> {
    if (values.map { it.name }.distinct().size != values.size || values.any { value -> declarations.none { it.name == value.name } })
        throw StoreFailure.InvalidUsage("Row keys do not match the declared schema")
    val supplied = values.associateBy { it.name }
    val scalars = declarations.filter { it !is StoreKey.CompositeKey }.associate { key ->
        val value = supplied[key.name] ?: if (key.nullable) BoundStoreKey.NullKey(key.name) else throw StoreFailure.InvalidUsage("Required row key is absent")
        val valid = when (value) {
            is BoundStoreKey.NullKey -> key.nullable
            is BoundStoreKey.StringKey -> key is StoreKey.StringKey
            is BoundStoreKey.LongKey -> key is StoreKey.LongKey
            is BoundStoreKey.IntegerKey -> key is StoreKey.IntegerKey
            is BoundStoreKey.BooleanKey -> key is StoreKey.BooleanKey
            is BoundStoreKey.SerializedKey -> key is StoreKey.SerializedKey
            is BoundStoreKey.CompositeKey -> false
        }
        if (!valid) throw StoreFailure.InvalidUsage("Row key representation does not match the schema")
        key.name to value
    }
    return declarations.map { key ->
        if (key is StoreKey.CompositeKey) {
            val derived = BoundStoreKey.CompositeKey(key.name, key.names, key.names.map { scalars.getValue(it) })
            supplied[key.name]?.let { if (it.toAny() != derived.toAny()) throw StoreFailure.InvalidUsage("Compound key does not match its components") }
            derived
        } else scalars.getValue(key.name)
    }
}
