package com.latenighthack.ktstore

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable

// Each BoundQuery/Select borrows one pooled connection for its whole lifetime (bind -> step -> finalize)
// and MUST return it in finalize(). SqlStoreDelegate always calls finalize() in a finally, so the
// connection is released even when a step/read throws.
open class BoundQuery(private val connection: Connection, val stmt: PreparedStatement, private val closeConnection: Boolean = true) : SqlBoundQuery {
    private var executed = false
    protected var resultSet: ResultSet? = null

    override suspend fun bindText(column: Int, value: String) {
        stmt.setString(column+1, value)
    }

    override suspend fun bindBytes(column: Int, value: ByteArray) {
        stmt.setBytes(column+1, value)
    }

    override suspend fun bindInt(column: Int, value: Long) {
        stmt.setLong(column+1, value)
    }

    override suspend fun step(): Boolean = withContext(Dispatchers.IO) {
        if (executed) {
            return@withContext resultSet!!.next()
        }

        executed = true
        if (!stmt.execute()) {
            return@withContext false
        }

        resultSet = stmt.resultSet
        resultSet!!.next()
    }

    // Closes the ResultSet + Statement and returns the connection to the pool. Each guarded so a
    // failure closing one still releases the rest (a leaked connection would starve the pool).
    override suspend fun finalize(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { resultSet?.close() }
        runCatching { stmt.close() }
        if (closeConnection) runCatching { connection.close() }
    }
}

class Select(connection: Connection, stmt: PreparedStatement, closeConnection: Boolean = true) : SqlSelect, BoundQuery(connection, stmt, closeConnection) {
    override suspend fun getBytes(column: Int): ByteArray {
        return resultSet!!.getBytes(column+1)
    }
}

// Pooled JDBC driver. Replaces the previous single DriverManager connection (no pool, no reconnect,
// and never closed statements/result sets) with a HikariCP pool: connections are validated, capped,
// recycled on maxLifetime, and every borrowed connection is returned in finalize()/use{}. This fixes
// both the statement/ResultSet leak and the "single connection dies -> permanent DB outage" failure.
class JdbcDriver(db: String, private val driver: String) : TransactionalSqlDriver {
    private class Transaction(val owner: JdbcDriver, val connection: Connection) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Transaction>
    }

    override suspend fun <T> transaction(block: suspend () -> T): T {
        if (coroutineContext[Transaction]?.owner === this) return block()
        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val result = withContext(Transaction(this@JdbcDriver, connection)) { block() }
                    connection.commit()
                    result
                } catch (error: Throwable) {
                    withContext(NonCancellable) { runCatching { connection.rollback() }.exceptionOrNull()?.let { error.addSuppressed(it) } }
                    throw error
                } finally { connection.autoCommit = true }
            }
        }
    }

    override suspend fun <T> transaction(lockKey: String, block: suspend () -> T): T = transaction {
        val connection = coroutineContext[Transaction]!!.connection
        if (driver == "postgresql") {
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
                it.setString(1, lockKey)
                it.execute()
            }
        } else {
            // SQLite serializes writers database-wide. Acquire the write lock before reading
            // state used for a claim, so two deferred transactions cannot both claim a lease.
            connection.createStatement().use {
                it.execute("CREATE TABLE IF NOT EXISTS ktstore_transaction_lock (id INTEGER PRIMARY KEY)")
                it.execute("INSERT OR IGNORE INTO ktstore_transaction_lock VALUES (1)")
                it.executeUpdate("UPDATE ktstore_transaction_lock SET id = id WHERE id = 1")
            }
        }
        block()
    }

    private val dataSource: HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:$driver:$db"
        poolName = "ktstore-$driver"
        maximumPoolSize = intProp("ktstore.pool.maxSize", 10)
        minimumIdle = intProp("ktstore.pool.minIdle", 1)
        connectionTimeout = longProp("ktstore.pool.connectionTimeoutMs", 30_000)
        maxLifetime = longProp("ktstore.pool.maxLifetimeMs", 1_800_000)   // 30 min; recycle before DB/proxy idle-kills
        keepaliveTime = longProp("ktstore.pool.keepaliveMs", 300_000)     // 5 min; detect/replace dead connections
        leakDetectionThreshold = longProp("ktstore.pool.leakDetectionMs", 0) // off unless opted in
    })

    override suspend fun createTable(statement: String): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute(statement) }
        }
    }

    override suspend fun dropTable(tableName: String): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DROP TABLE ${tableName}").use { it.execute() }
        }
    }

    override suspend fun selectAll(statement: String): SqlSelect = withContext(Dispatchers.IO) {
        val transaction = coroutineContext[Transaction]?.takeIf { it.owner === this@JdbcDriver }
        val conn = transaction?.connection ?: dataSource.connection
        try {
            Select(conn, conn.prepareStatement(statement), transaction == null)
        } catch (e: Throwable) {
            if (transaction == null) runCatching { conn.close() }
            throw e
        }
    }

    override suspend fun execute(statement: String): SqlBoundQuery = withContext(Dispatchers.IO) {
        val transaction = coroutineContext[Transaction]?.takeIf { it.owner === this@JdbcDriver }
        val conn = transaction?.connection ?: dataSource.connection
        try {
            BoundQuery(conn, conn.prepareStatement(statement), transaction == null)
        } catch (e: Throwable) {
            if (transaction == null) runCatching { conn.close() }
            throw e
        }
    }

    private fun intProp(name: String, default: Int): Int = System.getProperty(name)?.toIntOrNull() ?: default
    private fun longProp(name: String, default: Long): Long = System.getProperty(name)?.toLongOrNull() ?: default
}
