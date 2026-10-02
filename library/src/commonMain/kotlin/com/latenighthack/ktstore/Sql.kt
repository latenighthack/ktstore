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

interface ManagedSqlDriver : TransactionalSqlDriver {
    suspend fun close()
    suspend fun deleteDatabase()
}

interface TransactionalSqlDriver : SqlDriver {
    suspend fun <T> transaction(block: suspend () -> T): T
    suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T
}

interface SqlBoundQuery {
    suspend fun bindNull(column: Int): Unit = throw StoreFailure.InvalidUsage()
    suspend fun bindText(column: Int, value: String)
    suspend fun bindBytes(column: Int, value: ByteArray)
    suspend fun bindInt(column: Int, value: Long)

    suspend fun step(): Boolean
    suspend fun finalize()
}

interface SqlSelect : SqlBoundQuery {
    suspend fun getBytes(column: Int): ByteArray
    suspend fun getLong(column: Int): Long = throw StoreFailure.InvalidUsage()
    suspend fun getText(column: Int): String = throw StoreFailure.InvalidUsage()
}

class SqlStoreDelegate(private val driver: SqlDriver, private val blobType: String, configuration: DatabaseConfiguration? = null, private val legacyBinaryText: Boolean = false) : ScopedStoreDelegate, LifecycleStoreDelegate, IndexedQueryDelegate {
    private val configuration = configuration?.snapshot()
    override suspend fun close() = (driver as? ManagedSqlDriver ?: throw StoreFailure.InvalidUsage()).close()
    override suspend fun deleteDatabase() = (driver as? ManagedSqlDriver ?: throw StoreFailure.InvalidUsage()).deleteDatabase()
    override suspend fun <T> transaction(stores: Set<String>, mode: TransactionMode, block: suspend () -> T): T {
        if (!this.stores.map { it.tableName }.containsAll(stores)) throw StoreFailure.InvalidUsage()
        return transaction(block)
    }
    override val supportsTransactions get() = driver is TransactionalSqlDriver
    override suspend fun <T> transaction(block: suspend () -> T): T = try {
        (driver as? TransactionalSqlDriver ?: throw StoreFailure.InvalidUsage("SQL driver does not support transactions")).transaction(block)
    } catch (error: Throwable) { throw mapStorageFailure(error) }

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

    private suspend fun scalar(sql: String): Long {
        val query = driver.selectAll(sql)
        try { if (!query.step()) throw StoreFailure.Unavailable(); return query.getLong(0) }
        finally { query.finalize() }
    }

    override suspend fun createStores() {
        val config = configuration
        if (config == null) { stores.forEach { create(it) }; return }
        if (blobType != "BLOB" || driver !is ManagedSqlDriver) throw StoreFailure.InvalidUsage()
        val before = stores.toList()
        try {
            transaction {
                val persisted = scalar("PRAGMA user_version").toInt()
                val existing = scalar("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'") > 0
                val old = if (persisted == 0 && existing) config.legacyVersion ?: throw StoreFailure.Migration() else persisted
                if (old > config.version) throw StoreFailure.Migration()
                if (old == 0) stores.toList().forEach { create(it) }
                else config.steps(old).forEach { runMigration(SqlMigrationScope(), it.migrate) }
                if (old == config.version) {
                    val metadata = driver.selectAll("SELECT fingerprint FROM ktstore_schema WHERE id = 1")
                    try { if (!metadata.step() || metadata.getText(0) != config.fingerprint()) throw StoreFailure.Migration() }
                    finally { metadata.finalize() }
                }
                config.stores.forEach { declaration ->
                    val table = declaration.name.value
                    val query = driver.selectAll("PRAGMA index_list($table)")
                    val indices = mutableSetOf<String>()
                    try { while (query.step()) indices.add(query.getText(1)) } finally { query.finalize() }
                    if (!declaration.keys.all { "idx_${table}_${it.name}" in indices }) throw StoreFailure.Migration()
                    declaration.keys.forEach { key ->
                        val info = driver.selectAll("PRAGMA index_info(idx_${table}_${key.name})")
                        val columns = mutableListOf<String>()
                        try { while (info.step()) columns.add(info.getText(2)) } finally { info.finalize() }
                        val expected = if (key is StoreKey.CompositeKey) key.names else listOf(key.name)
                        if (columns != expected) throw StoreFailure.Migration()
                    }
                    val info = driver.selectAll("PRAGMA table_info($table)")
                    val columns = mutableMapOf<String, Triple<String, Boolean, Int>>()
                    try { while (info.step()) columns[info.getText(1)] = Triple(info.getText(2).uppercase(), info.getLong(3) != 0L, info.getLong(5).toInt()) }
                    finally { info.finalize() }
                    val scalarKeys = declaration.keys.filter { it !is StoreKey.CompositeKey }
                    if (columns.keys != (scalarKeys.map { it.name } + "__value").toSet()) throw StoreFailure.Migration()
                    scalarKeys.forEach { key ->
                        val column = columns.getValue(key.name)
                        val type = when (key) { is StoreKey.SerializedKey -> "BLOB"; is StoreKey.StringKey -> "TEXT"; else -> "INTEGER" }
                        if (column.first != type || column.second == key.nullable) throw StoreFailure.Migration()
                    }
                    val primary = declaration.primaryKey
                    val expectedPrimary = if (primary is StoreKey.CompositeKey) primary.names else listOf(primary.name)
                    if (columns.filterValues { it.third > 0 }.entries.sortedBy { it.value.third }.map { it.key } != expectedPrimary) throw StoreFailure.Migration()
                }
                if (old != config.version) {
                    driver.createTable("CREATE TABLE IF NOT EXISTS ktstore_schema (id INTEGER PRIMARY KEY, fingerprint TEXT NOT NULL)")
                    val metadata = driver.execute("REPLACE INTO ktstore_schema (id, fingerprint) VALUES (1, ?)")
                    try { metadata.bindText(0, config.fingerprint()); check(!metadata.step()) } finally { metadata.finalize() }
                }
                driver.createTable("PRAGMA user_version = ${config.version}")
            }
        } catch (error: Throwable) {
            stores.clear(); stores.addAll(before)
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw StoreFailure.Migration(error)
        }
    }

    private suspend fun create(store: TableDescriptor) {
        SqlHelper.generateCreateCommands(store.tableName, store.keys, store.primaryKey, blobType).forEach { driver.createTable(it) }
        if (configuration != null) store.keys.filter { it !is StoreKey.CompositeKey }.forEach { key ->
            val columns = (listOf(key.name) + (store.primaryKey?.columnName?.split(", ") ?: emptyList())).distinct()
            driver.createTable("CREATE INDEX IF NOT EXISTS idx_${store.tableName}_${key.name}_order ON ${store.tableName} (${columns.joinToString(",")})")
        }
    }

    private suspend fun migrationRows(table: String): List<ByteArray> {
        val select = driver.selectAll("SELECT __value FROM $table")
        try { return buildList { while (select.step()) add(select.getBytes(0)) } }
        finally { select.finalize() }
    }

    private inner class SqlMigrationScope : MigrationOperations {
        override suspend fun createStore(declaration: StoreDeclaration) {
            val descriptor = TableDescriptor(declaration.name.value, declaration.keys, declaration.primaryKey)
            create(descriptor)
            stores.removeAll { it.tableName == descriptor.tableName }; stores.add(descriptor)
        }
        override suspend fun removeStore(name: StoreName) { driver.dropTable(name.value); stores.removeAll { it.tableName == name.value } }
        override suspend fun addIndex(store: StoreName, key: StoreKey<*>) {
            val column = key.columnName
            driver.createTable("CREATE INDEX idx_${store.value}_${key.name} ON ${store.value} ($column)")
            if (key !is StoreKey.CompositeKey) {
                val primary = stores.first { it.tableName == store.value }.primaryKey!!
                val columns = (listOf(key.name) + if (primary is StoreKey.CompositeKey) primary.names else listOf(primary.name)).distinct()
                driver.createTable("CREATE INDEX idx_${store.value}_${key.name}_order ON ${store.value} (${columns.joinToString(",")})")
            }
        }
        override suspend fun removeIndex(store: StoreName, name: IndexName) {
            driver.createTable("DROP INDEX idx_${store.value}_${name.value}")
            driver.createTable("DROP INDEX IF EXISTS idx_${store.value}_${name.value}_order")
        }
        override suspend fun transform(store: StoreName, transform: (ByteArray) -> StoreRow) {
            val rows = migrationRows(store.value).map(transform)
            deleteAll(store.value)
            saveAll(store.value, rows)
        }
        override suspend fun rebuildStore(store: StoreName, declaration: StoreDeclaration, transform: (ByteArray) -> StoreRow) {
            val rows = migrationRows(store.value).map(transform)
            removeStore(store)
            createStore(declaration)
            saveAll(declaration.name.value, rows)
        }
    }

    override suspend fun destroyStores() {
        for (store in stores) {
            driver.dropTable(store.tableName)
        }
    }

    override suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?) {
        stores.removeAll { it.tableName == tableName }
        stores.add(TableDescriptor(tableName, keys.toList(), primaryKey))
    }

    override suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>) =
        saveAll(tableName, listOf(StoreRow(data, keys)))

    override suspend fun saveAll(tableName: String, rows: List<StoreRow>) {
        if (rows.isEmpty()) return
        transaction {
            val declaration = stores.first { it.tableName == tableName }
            saveRows(tableName, rows.map { StoreRow(it.data, normalizeKeys(declaration.keys, it.keys)) })
        }
    }

    private suspend fun saveRows(tableName: String, rows: List<StoreRow>) {
        // Bound parameters avoid hex-expanding megabyte payloads into SQL text.
        if (rows.isEmpty()) return
        val columns = rows.first().keys.filter { it !is BoundStoreKey.CompositeKey }.map { it.name }
        val pk = stores.first { it.tableName == tableName }.primaryKey!!.columnName
        val names = listOf("__value") + columns
        // Stay below SQLite/Postgres parameter limits and bound transient memory.
        val primary = stores.first { it.tableName == tableName }.primaryKey!!
        val primaryNames = if (primary is StoreKey.CompositeKey) primary.names else listOf(primary.name)
        // Deduplicate by content, including binary components; last input wins on every SQL dialect.
        val uniqueRows = rows.asReversed().distinctBy { row -> primaryNames.map { name -> row.keys.single { it.name == name }.toAny() } }.asReversed()
        uniqueRows.chunked(minOf(64, 900 / names.size).coerceAtLeast(1)).forEach { chunk ->
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
        if (relations.isEmpty()) return
        transaction { deleteRelations(tableName, relations) }
    }

    private suspend fun deleteRelations(tableName: String, relations: List<StoreRelation>) {
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
            is BoundStoreKey.NullKey -> bindNull(index)
            is BoundStoreKey.SerializedKey -> if (legacyBinaryText) bindText(index, SqlHelper.toBlobLiteral(key.value)) else bindBytes(index, key.value)
            is BoundStoreKey.StringKey -> bindText(index, key.value)
            is BoundStoreKey.BooleanKey -> bindInt(index, if (key.value) 1 else 0)
            is BoundStoreKey.IntegerKey -> bindInt(index, key.value.toLong())
            is BoundStoreKey.LongKey -> bindInt(index, key.value)
            is BoundStoreKey.CompositeKey -> error("Composite key must be flattened")
        }
    }

    override suspend fun getMany(tableName: String, relations: List<StoreRelation>): List<Any> =
        relations.flatMap { getAll(tableName, it) }

    private suspend fun queryKeys(table: String, query: IndexedQuery): Pair<StoreKey<*>, List<StoreKey<*>>> {
        val declaration = stores.first { it.tableName == table }
        val index = declaration.keys.singleOrNull { it.name == query.index.name } ?: throw StoreFailure.InvalidUsage()
        val primary = declaration.primaryKey ?: throw StoreFailure.InvalidUsage()
        val components = if (primary is StoreKey.CompositeKey) primary.names.map { name -> declaration.keys.single { it.name == name } } else listOf(primary)
        if (components.any { it is StoreKey.StringKey || it is StoreKey.LongKey }) throw StoreFailure.InvalidUsage("Ordered queries require sortable primary keys")
        if (configuration != null) {
            val inspection = driver.selectAll("PRAGMA index_info(idx_${table}_${index.name}_order)")
            val actual = mutableListOf<String>()
            try { while (inspection.step()) actual.add(inspection.getText(2)) } finally { inspection.finalize() }
            if (actual != (listOf(index.name) + components.map { it.name }).distinct())
                throw StoreFailure.InvalidUsage("Ordered query requires a migrated physical ordering index")
        }
        return index to components
    }
    private fun queryWhere(query: IndexedQuery, primary: List<StoreKey<*>>): Pair<String, List<BoundStoreKey>> {
        val clauses = mutableListOf("${query.index.name} IS NOT NULL")
        val args = mutableListOf<BoundStoreKey>()
        query.lower?.let { clauses.add("${query.index.name} ${if (it.inclusive) ">=" else ">"} ?"); args.add(it.key) }
        query.upper?.let { clauses.add("${query.index.name} ${if (it.inclusive) "<=" else "<"} ?"); args.add(it.key) }
        query.after?.let { token ->
            val columns = listOf(query.index.name) + primary.map { it.name }
            val keys = listOf(token.indexKey) + ((token.primaryKey as? BoundStoreKey.CompositeKey)?.values ?: listOf(token.primaryKey))
            val op = if (query.direction == SortDirection.ASCENDING) ">" else "<"
            clauses.add(columns.indices.joinToString(" OR ", "(", ")") { i ->
                val terms = (0..i).map { j -> args.add(keys[j]); "${columns[j]} ${if (j == i) op else "="} ?" }
                terms.joinToString(" AND ", "(", ")")
            })
        }
        return clauses.joinToString(" AND ") to args
    }
    private suspend fun SqlSelect.readKey(column: Int, key: StoreKey<*>): BoundStoreKey = when (key) {
        is StoreKey.SerializedKey -> key.bind(getBytes(column))
        is StoreKey.IntegerKey -> key.bind(getLong(column).toInt())
        is StoreKey.LongKey -> key.bind(getLong(column))
        is StoreKey.StringKey -> key.bind(getText(column))
        is StoreKey.BooleanKey -> key.bind(getLong(column) != 0L)
        else -> throw StoreFailure.InvalidUsage()
    }
    override suspend fun query(tableName: String, query: IndexedQuery, identity: String, version: Int): QueryPage {
        query.validate(identity, version, tableName)
        val (index, primary) = queryKeys(tableName, query)
        val (where, args) = queryWhere(query, primary)
        val columns = listOf(index) + primary
        val direction = if (query.direction == SortDirection.ASCENDING) "ASC" else "DESC"
        val sql = "SELECT __value, ${columns.joinToString(",") { it.name }} FROM $tableName WHERE $where ORDER BY ${columns.joinToString(",") { "${it.name} $direction" }} LIMIT ${query.limit.toLong() + 1}"
        val select = driver.selectAll(sql)
        val rows = mutableListOf<IndexedRow>()
        try {
            args.forEachIndexed { i, key -> select.bind(i, key) }
            while (select.step()) {
                val primaryValues = primary.mapIndexed { i, key -> select.readKey(i + 2, key) }
                val pk = stores.first { it.tableName == tableName }.primaryKey!!
                val primaryValue = if (pk is StoreKey.CompositeKey) BoundStoreKey.CompositeKey(pk.name, pk.names, primaryValues) else primaryValues.single()
                rows.add(IndexedRow(select.getBytes(0), select.readKey(1, index), primaryValue))
            }
        } finally { select.finalize() }
        return page(rows, query, identity, version, tableName)
    }
    override suspend fun count(tableName: String, query: IndexedQuery): Long {
        val (_, primary) = queryKeys(tableName, query)
        val (where, args) = queryWhere(query.copy(after = null), primary)
        val select = driver.selectAll("SELECT count(*) FROM $tableName WHERE $where")
        try { args.forEachIndexed { i, key -> select.bind(i, key) }; check(select.step()); return select.getLong(0) }
        finally { select.finalize() }
    }
    override suspend fun deleteBatch(tableName: String, query: IndexedQuery, identity: String, version: Int): Int = transaction {
        val result = query(tableName, query, identity, version)
        result.rows.forEach { delete(tableName, StoreRelation.Eq(it.primary)) }
        result.rows.size
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

    override suspend fun getAll(tableName: String, relation: StoreRelation?): List<Any> = transaction { readAll(tableName, relation) }

    private suspend fun readAll(tableName: String, relation: StoreRelation?): List<Any> {
        val clause = SqlHelper.convertToClause(relation)
        val whereClause = clause.where?.let { " WHERE $it" } ?: ""
        val declaration = stores.first { it.tableName == tableName }
        val primary = declaration.primaryKey
        val components = if (primary is StoreKey.CompositeKey) primary.names.map { name -> declaration.keys.single { it.name == name } } else listOfNotNull(primary)
        val columns = components.joinToString("") { ", ${it.name}" }
        val select = driver.selectAll("SELECT __value$columns FROM $tableName$whereClause;")
        val rows = mutableListOf<Pair<ByteArray, List<BoundStoreKey>>>()
        try {
            clause.args.forEachIndexed { index, key -> select.bind(index, key) }
            while (select.step()) rows.add(select.getBytes(0) to components.mapIndexed { i, key -> select.readKey(i + 1, key) })
        } finally { select.finalize() }
        return rows.sortedWith { a, b ->
            a.second.zip(b.second).firstNotNullOfOrNull { (x, y) -> compareKeys(x, y).takeIf { it != 0 } } ?: 0
        }.map { it.first }
    }

    override suspend fun deleteAll(tableName: String) = transaction { clearTable(tableName) }

    private suspend fun clearTable(tableName: String) {
        val delete = driver.execute("DELETE FROM $tableName")

        try {
            if (delete.step()) {
                throw Exception("failed to delete all")
            }
        } finally {
            delete.finalize()
        }
    }

    override suspend fun delete(tableName: String, relation: StoreRelation) = transaction { deleteRow(tableName, relation) }

    private suspend fun deleteRow(tableName: String, relation: StoreRelation) {
        val whereClause = SqlHelper.convertToClause(relation)
        val clause = whereClause.where.let {
            if (it != null) {
                " WHERE $it"
            } else {
                ""
            }
        }
        val delete = driver.execute("DELETE FROM $tableName$clause")

        try {
        whereClause.args
            .forEachIndexed { index, arg ->
                when (arg) {
                    is BoundStoreKey.NullKey -> throw StoreFailure.InvalidUsage()
                    is BoundStoreKey.SerializedKey -> if (legacyBinaryText) delete.bindText(index, SqlHelper.toBlobLiteral(arg.value)) else delete.bindBytes(index, arg.value)
                    is BoundStoreKey.StringKey -> delete.bindText(index, arg.value)
                    is BoundStoreKey.BooleanKey -> delete.bindInt(index, if (arg.value) 1 else 0)
                    is BoundStoreKey.IntegerKey -> delete.bindInt(index, arg.value.toLong())
                    is BoundStoreKey.LongKey -> delete.bindInt(index, arg.value)
                    is BoundStoreKey.CompositeKey -> throw StoreFailure.InvalidUsage("Composite predicates must be flattened")
                }
            }

            if (delete.step()) {
                throw Exception("failed to delete item")
            }
        } finally {
            delete.finalize()
        }
    }
}
