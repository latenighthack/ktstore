package com.latenighthack.ktstore

import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences

actual class PersistentKeyValueStoreDelegate actual constructor(private val storeName: String): KeyValueStoreDelegate {
    private val preferences: SharedPreferences = getAppContext!!().getSharedPreferences("", MODE_PRIVATE)

    actual override suspend fun getItem(key: String): String? {
        return preferences.getString("$storeName.$key", null)
    }

    actual override suspend fun saveItem(key: String, value: String) {
        with(preferences.edit()) {
            putString("$storeName.$key", value)
            commit()
        }
    }

    actual override suspend fun deleteItem(key: String) {
        with(preferences.edit()) {
            remove("$storeName.$key")
            commit()
        }
    }

    actual override suspend fun deleteAll() {
        with(preferences.edit()) {
            clear()
            commit()
        }
    }
}
