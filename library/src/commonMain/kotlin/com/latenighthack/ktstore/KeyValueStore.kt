package com.latenighthack.ktstore

import com.latenighthack.ktstore.binary.fromHexString
import com.latenighthack.ktstore.binary.toHexString

public interface KeyValueStoreDelegate {
    suspend fun getItem(key: String): String?

    suspend fun saveItem(key: String, value: String)

    suspend fun deleteItem(key: String)

    suspend fun deleteAll()
}

public class InMemoryKeyValueStoreDelegate: KeyValueStoreDelegate {
    private val store = mutableMapOf<String, String>()

    override suspend fun getItem(key: String): String? {
        return store[key]
    }

    override suspend fun saveItem(key: String, value: String) {
        store[key] = value
    }

    override suspend fun deleteItem(key: String) {
        store.remove(key)
    }

    override suspend fun deleteAll() {
        store.clear()
    }
}

public expect class PersistentKeyValueStoreDelegate(storeName: String): KeyValueStoreDelegate {
    override suspend fun getItem(key: String): String?

    override suspend fun saveItem(key: String, value: String)

    override suspend fun deleteItem(key: String)

    override suspend fun deleteAll()
}

public class KeyValueStore(private val delegate: KeyValueStoreDelegate) {
    suspend fun <T> get(key: String, deserializer: (ByteArray) -> T): T? {
        val value = delegate.getItem(key)

        return value?.let {
            deserializer(it.fromHexString())
        }
    }

    suspend fun <T : Any> save(key: String, value: T, serializer: (T) -> ByteArray) {
        val preparedValue = serializer(value).toHexString()

        delegate.saveItem(key, preparedValue)
    }

    suspend fun <T> delete(key: String) {
        delegate.deleteItem(key)
    }

    suspend fun deleteAll() {
        delegate.deleteAll()
    }
}
