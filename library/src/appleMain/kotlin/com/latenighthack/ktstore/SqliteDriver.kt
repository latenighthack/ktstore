package com.latenighthack.ktstore

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import sqlite3.*
import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import kotlinx.coroutines.withContext
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
open class NativeSqlBoundQuery constructor(val preparedStatement: CPointer<sqlite3_stmt>) : SqlBoundQuery {

    companion object {
        @OptIn(ExperimentalForeignApi::class)
        private val SQLITE_TRANSIENT = staticCFunction { _: COpaquePointer? -> }
    }

    override suspend fun bindBytes(column: Int, value: ByteArray) {
        withContext(Dispatchers.Main) {
            val result = value.usePinned { pinned ->
                sqlite3_bind_blob(preparedStatement, column + 1, pinned.addressOf(0), value.size.toInt(), SQLITE_TRANSIENT)
            }
            if (result != SQLITE_OK) {
                throw IllegalStateException("Can't bind blob")
            }
        }
    }

    override suspend fun bindInt(column: Int, value: Long) {
        withContext(Dispatchers.Main) {
            if (sqlite3_bind_int64(preparedStatement, column + 1, value) != SQLITE_OK) {
                throw IllegalStateException("Can't bind int")
            }
        }
    }

    override suspend fun bindText(column: Int, value: String) {
        withContext(Dispatchers.Main) {
            val result = sqlite3_bind_text(preparedStatement, column + 1, value, -1, SQLITE_TRANSIENT)
            if (result != SQLITE_OK) {
                throw IllegalStateException("Can't bind text")
            }
        }
    }

    override suspend fun finalize() {
        withContext(Dispatchers.Main) {
            if (sqlite3_finalize(preparedStatement) != SQLITE_OK) {
                throw IllegalStateException("Failed to finalize statement")
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun step(): Boolean {
        return withContext(Dispatchers.Main) {
            val result = sqlite3_step(preparedStatement)
            result != SQLITE_DONE
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
class NativeSqlSelect(statement: CPointer<sqlite3_stmt>) : NativeSqlBoundQuery(statement), SqlSelect {
    override suspend fun getBytes(column: Int): ByteArray {
        return withContext(Dispatchers.Main) {
            val size = sqlite3_column_bytes(this@NativeSqlSelect.preparedStatement, column + 1)
            val pointer = sqlite3_column_blob(preparedStatement, column + 1)

            pointer?.readBytes(size.toInt()) ?: ByteArray(0)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
class SqliteDriver(path: String) : SqlDriver {

    private lateinit var db: CPointer<sqlite3>

    init {
        memScoped {
            val dbPtr = alloc<CPointerVar<sqlite3>>()
            val directory = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

            val newUrl = NSString.stringWithString("$directory/$path")

            if (sqlite3_open(filename = newUrl, ppDb = dbPtr.ptr) != SQLITE_OK) {
                throw IllegalStateException("Database not opened")
            }

            db = dbPtr.value!!
        }
    }

    override suspend fun createTable(statement: String) {
        withContext(Dispatchers.Main) {
            val createTableStatement = prepareStatement(statement)
            if (sqlite3_step(createTableStatement) != SQLITE_DONE) {
                sqlite3_finalize(createTableStatement)
                throw IllegalStateException("Table not created")
            }
            sqlite3_finalize(createTableStatement)
        }
    }

    override suspend fun dropTable(tableName: String) {
        withContext(Dispatchers.Main) {
            val statement = "DROP TABLE $tableName;"
            val dropTableStatement = prepareStatement(statement)
            if (sqlite3_step(dropTableStatement) != SQLITE_DONE) {
                val errorMessage = "Failed to drop table $tableName"
                sqlite3_finalize(dropTableStatement)
                throw IllegalStateException(errorMessage)
            }
            sqlite3_finalize(dropTableStatement)
        }
    }

    override suspend fun execute(statement: String): SqlBoundQuery {
        return withContext(Dispatchers.Main) {
            val preparedStatement = prepareStatement(statement)
            NativeSqlBoundQuery(preparedStatement)
        }
    }

    override suspend fun selectAll(statement: String): SqlSelect {
        return withContext(Dispatchers.Main) {
            val preparedStatement = prepareStatement(statement)
            NativeSqlSelect(preparedStatement)
        }
    }

    private fun prepareStatement(statement: String): CPointer<sqlite3_stmt> {
        val preparedStatement = nativeHeap.alloc<CPointerVar<sqlite3_stmt>>()
        if (sqlite3_prepare_v2(db, statement, -1, preparedStatement.ptr, null) != SQLITE_OK) {
            val errorMessage = "Database returned error ${sqlite3_errcode(db)}: ${sqlite3_errmsg(db)?.toKString()}"
            throw IllegalStateException(errorMessage)
        }
        return preparedStatement.value ?: throw IllegalStateException("Statement preparation failed")
    }

    protected fun finalize() {
        sqlite3_close(db)
    }
}
