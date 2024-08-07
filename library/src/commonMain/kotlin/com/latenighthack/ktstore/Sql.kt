package com.latenighthack.ktstore

interface SqlDriver {
    suspend fun createTable(statement: String)
    suspend fun dropTable(tableName: String)

    suspend fun selectAll(statement: String): SqlSelect
    suspend fun execute(statement: String): SqlBoundQuery
}

interface SqlBoundQuery {
    suspend fun bindText(column: Int, value: String)
    suspend fun bindBytes(column: Int, value: ByteArray)
    suspend fun bindInt(column: Int, value: Long)

    suspend fun step(): Boolean
    suspend fun finalize()
}

interface SqlSelect : SqlBoundQuery {
    suspend fun getBytes(column: Int): ByteArray
}

class SqlStoreDelegate(private val driver: SqlDriver) : StoreDelegate {
    private val stores = mutableListOf<TableDescriptor>()
    override val isSerialized: Boolean
        get() = true

    private data class TableDescriptor(
        val tableName: String,
        val keys: List<StoreKey<*>>,
        val primaryKey: StoreKey<*>?
    )

    override suspend fun createStores() {
        for (store in stores) {
            val statement = SqlHelper.generateCreateCommand(store.tableName, store.keys, store.primaryKey)
            driver.createTable(statement)
        }
    }

    override suspend fun destroyStores() {
        for (store in stores) {
            driver.dropTable(store.tableName)
        }
    }

    override suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?) {
        val statement = SqlHelper.generateCreateCommand(tableName, keys, primaryKey)

        driver.createTable(statement)

        stores.add(TableDescriptor(tableName, keys, primaryKey))
    }

    override suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>) {
        val nonCompositeKeys = keys.filter { it !is BoundStoreKey.CompositeKey }
        val insertKeys = listOf("__value", *(nonCompositeKeys.map { it.name }).toTypedArray())
            .joinToString(", ")
        val insertValues = listOf(SqlHelper.toBlobLiteral(data as ByteArray))
            .plus(nonCompositeKeys.map {
                SqlHelper.convertKey(it)
            })
            .joinToString(", ")

        val insertStatement = "REPLACE INTO $tableName ($insertKeys) VALUES ($insertValues);"
        val insert = driver.execute(insertStatement)

        try {
            if (insert.step()) {
                throw Exception("failed to save item")
            }
        } finally {
            insert.finalize()
        }
    }

    override suspend fun get(tableName: String, relation: StoreRelation?): Any? {
        return getAll(tableName, relation).let {
            if (it.isNotEmpty()) {
                it[0]
            } else {
                null
            }
        }
    }

    override suspend fun getAll(tableName: String, relation: StoreRelation?): List<Any> {
        val clause = SqlHelper.convertToClause(relation)
        val whereClause = clause.let {
            if (it.where != null) {
                val mappedClauses = it.where.split("?")
                    .mapIndexed { index, subclause ->
                        if (index < clause.args.size) {
                            subclause + SqlHelper.convertKey(clause.args[index])
                        } else {
                            ""
                        }
                    }
                    .joinToString("")

                " WHERE $mappedClauses"
            } else {
                ""
            }
        }

        val select = driver.selectAll("SELECT __value, * FROM $tableName$whereClause;")
        val rows = mutableListOf<ByteArray>()

        while (select.step()) {
            val value = select.getBytes(0)

            rows.add(value)
        }

        select.finalize()

        return rows
    }

    override suspend fun deleteAll(tableName: String) {
        val delete = driver.execute("DELETE FROM $tableName")

        try {
            if (delete.step()) {
                throw Exception("failed to delete all")
            }
        } finally {
            delete.finalize()
        }
    }

    override suspend fun delete(tableName: String, relation: StoreRelation) {
        val whereClause = SqlHelper.convertToClause(relation)
        val clause = whereClause.where.let {
            if (it != null) {
                " WHERE $it"
            } else {
                ""
            }
        }
        val delete = driver.execute("DELETE FROM $tableName$clause")

        whereClause.args
            .forEachIndexed { index, arg ->
                when (arg) {
                    is BoundStoreKey.SerializedKey -> delete.bindBytes(index, arg.value)
                    is BoundStoreKey.StringKey -> delete.bindText(index, arg.value)
                    is BoundStoreKey.BooleanKey -> delete.bindInt(index, if (arg.value) 1 else 0)
                    is BoundStoreKey.IntegerKey -> delete.bindInt(index, arg.value.toLong())
                    is BoundStoreKey.LongKey -> delete.bindInt(index, arg.value)
                    is BoundStoreKey.CompositeKey -> TODO()
                }
            }

        try {
            if (delete.step()) {
                throw Exception("failed to delete item")
            }
        } finally {
            delete.finalize()
        }
    }
}
