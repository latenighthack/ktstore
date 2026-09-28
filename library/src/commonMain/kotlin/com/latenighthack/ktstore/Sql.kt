package com.latenighthack.ktstore

private val <T> StoreKey<T>.columnName: String
    get() = when (this) {
        is StoreKey.CompositeKey -> names.joinToString(", ")
        else -> name
    }

interface SqlDriver {
    suspend fun createTable(statement: String)
    suspend fun dropTable(tableName: String)

    suspend fun selectAll(statement: String): SqlSelect
    suspend fun execute(statement: String): SqlBoundQuery
}

interface TransactionalSqlDriver : SqlDriver {
    suspend fun <T> transaction(block: suspend () -> T): T
    suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T
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

class SqlStoreDelegate(private val driver: SqlDriver, private val blobType: String) : TransactionalStoreDelegate {
    override val supportsTransactions get() = driver is TransactionalSqlDriver
    override suspend fun <T> transaction(block: suspend () -> T): T =
        (driver as? TransactionalSqlDriver ?: error("SQL driver does not support transactions")).transaction(block)

    override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T =
        (driver as? TransactionalSqlDriver ?: error("SQL driver does not support transactions")).transaction(lockKey, block)

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
            val statement = SqlHelper.generateCreateCommand(store.tableName, store.keys, store.primaryKey, blobType = blobType)
            driver.createTable(statement)
        }
    }

    override suspend fun destroyStores() {
        for (store in stores) {
            driver.dropTable(store.tableName)
        }
    }

    override suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?) {
        stores.add(TableDescriptor(tableName, keys, primaryKey))
    }

    override suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>) =
        saveAll(tableName, listOf(StoreRow(data, keys)))

    override suspend fun saveAll(tableName: String, rows: List<StoreRow>) {
        // Bound parameters avoid hex-expanding megabyte payloads into SQL text.
        if (rows.isEmpty()) return
        val columns = rows.first().keys.filter { it !is BoundStoreKey.CompositeKey }.map { it.name }
        val pk = stores.first { it.tableName == tableName }.primaryKey!!.columnName
        val names = listOf("__value") + columns
        // Stay below SQLite/Postgres parameter limits and bound transient memory.
        rows.chunked(minOf(64, 900 / names.size).coerceAtLeast(1)).forEach { chunk ->
            require(chunk.all { row -> row.keys.filter { it !is BoundStoreKey.CompositeKey }.map { it.name } == columns })
            val values = chunk.joinToString(",") { "(" + names.joinToString(",") { "?" } + ")" }
            val sql = if (blobType == "BYTEA") {
                "INSERT INTO $tableName (${names.joinToString(",")}) VALUES $values ON CONFLICT ($pk) DO UPDATE SET " +
                    names.joinToString(",") { "$it = EXCLUDED.$it" }
            } else "REPLACE INTO $tableName (${names.joinToString(",")}) VALUES $values"
            val query = driver.execute(sql)
            try {
                var index = 0
                chunk.forEach { row ->
                    query.bindBytes(index++, row.data as ByteArray)
                    row.keys.filter { it !is BoundStoreKey.CompositeKey }.forEach { query.bind(index++, it) }
                }
                check(!query.step()) { "Unexpected rows from upsert" }
            } finally { query.finalize() }
        }
    }

    override suspend fun deleteMany(tableName: String, relations: List<StoreRelation>) {
        relations.chunked(64).forEach { chunk ->
            val clauses = chunk.map { SqlHelper.convertToClause(it) }
            val query = driver.execute("DELETE FROM $tableName WHERE " + clauses.joinToString(" OR ") { "(${it.where})" })
            try {
                clauses.flatMap { it.args.asList() }.forEachIndexed { i, key -> query.bind(i, key) }
                check(!query.step())
            } finally { query.finalize() }
        }
    }

    private suspend fun SqlBoundQuery.bind(index: Int, key: BoundStoreKey) {
        when (key) {
            is BoundStoreKey.SerializedKey -> bindBytes(index, key.value)
            is BoundStoreKey.StringKey -> bindText(index, key.value)
            is BoundStoreKey.BooleanKey -> bindInt(index, if (key.value) 1 else 0)
            is BoundStoreKey.IntegerKey -> bindInt(index, key.value.toLong())
            is BoundStoreKey.LongKey -> bindInt(index, key.value)
            is BoundStoreKey.CompositeKey -> error("Composite key must be flattened")
        }
    }

    override suspend fun getMany(tableName: String, relations: List<StoreRelation>): List<Any> {
        val rows = mutableListOf<ByteArray>()
        for (chunk in relations.chunked(64)) {
            val clauses = chunk.map { SqlHelper.convertToClause(it) }
            val query = driver.selectAll("SELECT __value FROM $tableName WHERE " + clauses.joinToString(" OR ") { "(${it.where})" })
            try {
                clauses.flatMap { it.args.asList() }.forEachIndexed { index, key -> query.bind(index, key) }
                while (query.step()) rows.add(query.getBytes(0))
            } finally { query.finalize() }
        }
        return rows
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
        val whereClause = clause.where?.let { " WHERE $it" } ?: ""
        val select = driver.selectAll("SELECT __value FROM $tableName$whereClause;")
        val rows = mutableListOf<ByteArray>()

        try {
            clause.args.forEachIndexed { index, key -> select.bind(index, key) }
            while (select.step()) {
                val value = select.getBytes(0)

                rows.add(value)
            }
        } finally {
            // Always release the borrowed connection, even if step()/getBytes() throws mid-iteration.
            select.finalize()
        }

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
