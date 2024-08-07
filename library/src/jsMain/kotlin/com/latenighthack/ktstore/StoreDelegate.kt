package com.latenighthack.ktstore

import kotlinx.browser.window
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise
import kotlin.js.json

suspend fun <T> Promise<T>.await(): T = suspendCoroutine { cont ->
    then({ cont.resume(it) }, { cont.resumeWithException(it) })
}

actual fun createStoreDelegate(db: String): StoreDelegate {
    return IndexDB(db)
}

class IndexDB(private val databaseName: String) : StoreDelegate {
    private val indexedDB = js("window.indexedDB")
    private var pendingOpen: Promise<dynamic>? = null

    private suspend fun openPromise(onUpgrade: (dynamic) -> Unit): dynamic {
        val openRequest = indexedDB.open(databaseName, 1)
        val promise = Promise<dynamic> { resolve, reject ->
            openRequest.onupgradeneeded = {
                onUpgrade(openRequest.result)
            }

            openRequest.onsuccess = {
                resolve(openRequest.result)
            }
        }

        pendingOpen = promise

        return promise.await()
    }

    private suspend fun awaitDb(): dynamic {
        return pendingOpen!!.await()
    }

    override val isSerialized: Boolean
        get() = true

    private val onUpgradeCallbacks = mutableListOf<(dynamic) -> Unit>()
    private val tableNames = mutableListOf<String>()

    override suspend fun createStores() {
        openPromise { db ->
            onUpgradeCallbacks.forEach { it(db) }
        }
    }

    override suspend fun destroyStores() {
        openPromise { db ->
            for (tableName in tableNames) {
                db.deleteObjectStore(tableName)
            }
        }
    }

    override suspend fun registerStore(tableName: String, keys: List<StoreKey<*>>, primaryKey: StoreKey<*>?) {
        tableNames.add(tableName)
        onUpgradeCallbacks.add { db ->
            if (!db.objectStoreNames.contains(tableName) as Boolean) {
                val keyJson = if (primaryKey == null) {
                    json(
                        "keyPath" to "_rowid",
                        "autoIncrement" to true
                    )
                } else {
                    val keyName = if (primaryKey is StoreKey.CompositeKey) {
                        "composite_${primaryKey.names.joinToString("_")}"
                    } else {
                        primaryKey.name
                    }

                    json("keyPath" to keyName)
                }

                val objectStore = db.createObjectStore(
                    tableName, keyJson
                )

                keys.forEach {
                    if (it is StoreKey.CompositeKey) {
                        val compositeName = it.names.joinToString("_")

                        objectStore.createIndex(
                            "idx_${compositeName}",
                            (it.names.map { "${it}" }).toTypedArray(),
                            json("unique" to false)
                        )
                    } else {
                        objectStore.createIndex("idx_${it.name}", "${it.name}", json("unique" to false))
                    }
                }
            }
        }
        // number, date, string, binary, or array
    }

    override suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>) {
        val db = awaitDb()
        val tx = db.transaction(tableName, "readwrite")
        val objectStore = tx.objectStore(tableName)
        val dynamicData = json("_value" to data)

        // bind secondary index values
        keys.forEach {
            dynamicData[it.name] = getValue(it)
        }

        val addPromise = Promise<dynamic> { resolve, reject ->
            try {
                val request = objectStore.put(dynamicData)

                request.onsuccess = { e: dynamic ->
                    resolve(e.target.result)
                }

                request.onerror = { error: dynamic ->
                    reject(error)
                }
            } catch (error: dynamic) {
                console.error("Error saving in table=$tableName, keys=${keys.map { it.name }.joinToString(", ")}")
                reject(error)
            }
        }

        addPromise.await()
    }

    override suspend fun get(tableName: String, relation: StoreRelation?): Any? {
        val results = getAll(tableName, relation)

        return if (results.isNotEmpty()) {
            results[0]
        } else {
            null
        }
    }

    private fun getValue(boundStoreKey: BoundStoreKey): dynamic {
        return when (boundStoreKey) {
            is BoundStoreKey.SerializedKey -> boundStoreKey.value
            is BoundStoreKey.StringKey -> boundStoreKey.value
            is BoundStoreKey.BooleanKey -> boundStoreKey.value
            is BoundStoreKey.IntegerKey -> boundStoreKey.value
            is BoundStoreKey.LongKey -> boundStoreKey.value.toString()
            is BoundStoreKey.CompositeKey -> boundStoreKey.values.map { getValue(it) }.toTypedArray()
        }
    }

    override suspend fun getAll(tableName: String, relation: StoreRelation?): List<Any> {
        val db = awaitDb()
        val tx = db.transaction(tableName, "readonly")
        val objectStore = tx.objectStore(tableName)

        val request = if (relation != null) {
            val key = relation.key
            val indexName = if (key is BoundStoreKey.CompositeKey) {
                "idx_${key.names.joinToString("_")}"
            } else {
                "idx_${key.name}"
            }

            val index = objectStore.index(indexName)
            val eqValue = getValue(key)

            index.getAll(window.asDynamic().IDBKeyRange.only(eqValue))
        } else {
            objectStore.getAll()
        }

        val getAllPromise = Promise<dynamic> { resolve, reject ->
            request.onsuccess = { e: dynamic ->
                resolve(e.target.result)
            }

            request.onerror = { error: dynamic ->
                reject(error)
            }
        }

        return (getAllPromise.await() as Array<dynamic>).map {
            it["_value"]
        }.toList()
    }

    override suspend fun deleteAll(tableName: String) {
        val db = awaitDb()
        val tx = db.transaction(tableName, "readwrite")
        val objectStore = tx.objectStore(tableName)

        val request = objectStore.clear()
        val deletePromise = Promise<dynamic> { resolve, reject ->
            request.onsuccess = { e: dynamic ->
                resolve(e.target.result)
            }

            request.onerror = { error: dynamic ->
                reject(error)
            }
        }

        deletePromise.await()
    }

    override suspend fun delete(tableName: String, relation: StoreRelation) {
        val db = awaitDb()
        val tx = db.transaction(tableName, "readwrite")
        val objectStore = tx.objectStore(tableName)

        val key = relation.key
        val indexName = if (key is BoundStoreKey.CompositeKey) {
            "idx_${key.names.joinToString("_")}"
        } else {
            "idx_${key.name}"
        }

        val eqValue = getValue(key)

        val request = objectStore.delete(window.asDynamic().IDBKeyRange.only(eqValue))

        val deletePromise = Promise<dynamic> { resolve, reject ->
            request.onsuccess = { e: dynamic ->
                resolve(e.target.result)
            }

            request.onerror = { error: dynamic ->
                reject(error)
            }
        }

        deletePromise.await()
    }
}
