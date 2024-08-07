package com.latenighthack.ktstore

data class WhereClause(val where: String? = null, val args: Array<BoundStoreKey> = arrayOf())

object SqlHelper {
    private const val VALUE_COLUMN_NAME = "__value"
    private val hexCharLookup = "0123456789ABCDEF".toCharArray()

    fun toBlobLiteral(blob: ByteArray): String {
        val builder = StringBuilder()

        builder.append('X')
        builder.append('\'')

        for (byte in blob) {
            val highNybble = (byte.toInt() and 0xf0) shr 4
            val lowNybble = (byte.toInt() and 0x0f)

            builder.append(hexCharLookup[highNybble])
            builder.append(hexCharLookup[lowNybble])
        }
        builder.append('\'')

        return builder.toString()
    }

    fun generateDropCommand(tableName: String): String {
        return "DROP TABLE $tableName;"
    }

    fun generateCreateCommand(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?): String {
        val columns = listOf(
            "$VALUE_COLUMN_NAME BLOB NOT NULL"
        ) + keys.mapNotNull { key ->
            val type = when (key) {
                is StoreKey.SerializedKey -> "BLOB"
                is StoreKey.StringKey -> "TEXT"
                is StoreKey.BooleanKey -> "INTEGER"
                is StoreKey.IntegerKey -> "INTEGER"
                is StoreKey.LongKey -> "INTEGER"
                is StoreKey.CompositeKey -> null
            }

            type?.let { "${key.name} $it NOT NULL" }
        }
        val standardIndices = keys
            .filter { it !is StoreKey.CompositeKey }
            .map {
                "CREATE INDEX IF NOT EXISTS idx_${it.name} ON $tableName (${it.name});"
            }
        val compositeIndices = keys
            .filterIsInstance<StoreKey.CompositeKey>()
            .map {
                "CREATE INDEX IF NOT EXISTS idx_${it.name} ON $tableName (${it.names.joinToString(", ")});"
            }
        val primaryKeyStatement = primaryKey?.let {
            val primaryKeys = if (it is StoreKey.CompositeKey) {
                it.names.joinToString(", ")
            } else {
                it.name
            }

            ", PRIMARY KEY ($primaryKeys)"
        } ?: ""

        val commands =
            listOf("CREATE TABLE IF NOT EXISTS $tableName (${columns.joinToString(", ")}${primaryKeyStatement});") + standardIndices + compositeIndices

        return commands.joinToString("\n")
    }

    fun convertKey(key: BoundStoreKey): String {
        return when (key) {
            is BoundStoreKey.SerializedKey -> toBlobLiteral(key.value)
            is BoundStoreKey.StringKey -> "'${key.value.replace("'", "\\'")}'"
            is BoundStoreKey.BooleanKey -> if (key.value) "1" else "0"
            is BoundStoreKey.IntegerKey -> "${key.value}"
            is BoundStoreKey.LongKey -> "${key.value}"
            else -> throw UnsupportedOperationException()
        }
    }

    fun convertToClause(relation: StoreRelation?): WhereClause {
        val selection: String?
        val selectionArgs: Array<BoundStoreKey>

        if (relation == null) {
            return WhereClause(null)
        }

        val key = relation.key
        if (key is BoundStoreKey.CompositeKey) {
            selection = key.values
                .map {
                    val keyName = it.name

                    when (relation) {
                        is StoreRelation.Eq -> "$keyName = ?"
                    }
                }
                .joinToString(" AND ")
            selectionArgs = key.values
                .map {
                    it//mapKeyValue(it)
                }
                .toTypedArray()
        } else {
            val keyName = key.name

            selection = when (relation) {
                is StoreRelation.Eq -> "$keyName = ?"
            }
            selectionArgs = arrayOf(key)
        }

        return WhereClause(selection, selectionArgs)
    }
}
