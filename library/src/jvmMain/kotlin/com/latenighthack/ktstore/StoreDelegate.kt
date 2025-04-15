package com.latenighthack.ktstore

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

open class BoundQuery(val stmt: PreparedStatement) : SqlBoundQuery {
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

    override suspend fun step(): Boolean {
        if (executed) {
            return resultSet!!.next()
        }

        executed = true
        if (!stmt.execute()) {
            return false
        }

        resultSet = stmt.resultSet
        return resultSet!!.next()
    }

    override suspend fun finalize() {
        stmt.clearParameters()
    }
}

class Select(stmt: PreparedStatement) : SqlSelect, BoundQuery(stmt) {
    override suspend fun getBytes(column: Int): ByteArray {
        return resultSet!!.getBytes(column+1)
    }
}

class JdbcDriver(db: String, driver: String) : SqlDriver {
    private val connection: Connection = DriverManager.getConnection("jdbc:$driver:$db")

    override suspend fun createTable(statement: String) {
        val stmt = connection.createStatement()
        stmt.execute(statement)
    }

    override suspend fun dropTable(tableName: String) {
        val stmt = connection.prepareStatement("DROP TABLE ${tableName}")
        stmt.execute()
    }

    override suspend fun selectAll(statement: String): SqlSelect {
        val stmt = connection.prepareStatement(statement)

        return Select(stmt)
    }

    override suspend fun execute(statement: String): SqlBoundQuery {
        return BoundQuery(connection.prepareStatement(statement))
    }
}
