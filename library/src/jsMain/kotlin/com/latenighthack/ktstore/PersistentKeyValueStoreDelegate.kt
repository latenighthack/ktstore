package com.latenighthack.ktstore

import kotlinx.browser.window

actual class PersistentKeyValueStoreDelegate actual constructor(private val storeName: String): KeyValueStoreDelegate {
    actual override suspend fun getItem(key: String): String? {
        return window.localStorage.getItem("$storeName.$key")
    }

    actual override suspend fun saveItem(key: String, value: String) {
        window.localStorage.setItem("$storeName.$key", value)
    }

    actual override suspend fun deleteItem(key: String) {
        window.localStorage.removeItem("$storeName.$key")
    }

    actual override suspend fun deleteAll() {
        val keys = mutableListOf<String>()

        for (i in 0 until window.localStorage.length) {
            keys.add(window.localStorage.key(i)!!)
        }

        for (key in keys) {
            if (key.startsWith("$storeName.")) {
                window.localStorage.removeItem(key)
            }
        }
    }
}
