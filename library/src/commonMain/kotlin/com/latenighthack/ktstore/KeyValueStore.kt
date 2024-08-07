package com.latenighthack.ktstore

import com.latenighthack.ktstore.binary.fromHexString
import com.latenighthack.ktstore.binary.toHexString

public expect class KeyValueStoreDelegate {
    fun getItem(key: String): String?

    fun saveItem(key: String, value: String)

    fun deleteItem(key: String)

    fun deleteAll()
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
