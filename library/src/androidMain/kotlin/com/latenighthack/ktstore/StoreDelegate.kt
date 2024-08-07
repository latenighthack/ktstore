package com.latenighthack.ktstore

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SqliteStoreDelegate(
    private val context: Context,
    private val dbName: String
) : StoreDelegate {
    companion object {
        private const val VALUE_COLUMN_NAME = "__value"
    }

    private var openHelper: SQLiteOpenHelper? = null
    private val stores = mutableListOf<TableDescriptor>()

    private data class TableDescriptor(
        val tableName: String,
        val keys: List<StoreKey<*>>,
        val primaryKey: StoreKey<*>?
    )

    fun recreate() {
        if (openHelper == null) {
            openHelper = SQLite()
        }
    }

    fun close() {
        openHelper?.close()
        openHelper = null
    }

    inner class SQLite : SQLiteOpenHelper(context, dbName, null, 2, null) {
        override fun onCreate(p0: SQLiteDatabase?) {
        }

        override fun onUpgrade(p0: SQLiteDatabase?, p1: Int, p2: Int) {
        }
    }

    init {
        recreate()
    }

    override val isSerialized: Boolean
        get() = true

    private suspend fun withReadable(block: (SQLiteDatabase) -> Unit) {
        block(openHelper!!.readableDatabase)
    }

    private suspend fun withWriteable(block: (SQLiteDatabase) -> Unit) {
        val db = openHelper!!.writableDatabase

        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?) {
        val createCommand = SqlHelper.generateCreateCommand(tableName, keys, primaryKey)

        stores.add(TableDescriptor(tableName, keys, primaryKey))

        withWriteable { db ->
            db.execSQL(createCommand)
        }
    }

    override suspend fun createStores() {
        withWriteable { db ->
            for (store in stores) {
                val createCommand = SqlHelper.generateCreateCommand(store.tableName, store.keys, store.primaryKey)
                db.execSQL(createCommand)
            }
        }
    }

    override suspend fun destroyStores() {
        withWriteable { db ->
            for (store in stores) {
                val dropCommand = SqlHelper.generateDropCommand(store.tableName)

                db.execSQL(dropCommand)
            }
        }
    }

    override suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>) {
        val contentValues = ContentValues().apply {
            put(VALUE_COLUMN_NAME, data as ByteArray)
            keys.forEach {
                when (it) {
                    is BoundStoreKey.SerializedKey -> put(it.name, SqlHelper.toBlobLiteral(it.value))
                    is BoundStoreKey.StringKey -> put(it.name, it.value)
                    is BoundStoreKey.BooleanKey -> put(it.name, it.value)
                    is BoundStoreKey.IntegerKey -> put(it.name, it.value)
                    is BoundStoreKey.LongKey -> put(it.name, it.value)
                    else -> {}
                }
            }
        }

        withWriteable { db ->
            db.replace(tableName, null, contentValues)
        }
    }

    override suspend fun get(tableName: String, relation: StoreRelation?): Any? {
        val entries = getAll(tableName, relation)

        return if (entries.isEmpty()) {
            null
        } else {
            entries[0]
        }
    }

    override suspend fun getAll(tableName: String, relation: StoreRelation?): List<Any> {
        val data = mutableListOf<ByteArray>()

        withReadable { db ->
            val whereClause = SqlHelper.convertToClause(relation)
            val cursor = db.query(
                tableName, null,
                whereClause.where, whereClause.args.map {
                    SqlHelper.convertKey(it)
                }.toTypedArray(),
                null, null, null
            )

            with(cursor) {
                val valueIndex = getColumnIndexOrThrow(VALUE_COLUMN_NAME)
                while (moveToNext()) {
                    val value = getBlob(valueIndex)

                    data.add(value)
                }
            }
        }

        return data
    }

    override suspend fun deleteAll(tableName: String) {
        withWriteable { db ->
            db.delete(tableName, "", emptyArray())
        }
    }

    override suspend fun delete(tableName: String, relation: StoreRelation) {
        val whereClause = SqlHelper.convertToClause(relation)

        withWriteable { db ->
            db.delete(tableName, whereClause.where, whereClause.args.map {
                SqlHelper.convertKey(it)
            }.toTypedArray())
        }
    }
}
