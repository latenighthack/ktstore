package com.latenighthack.ktstore

import android.content.SharedPreferences

actual class KeyValueStoreDelegate(private val preferences: SharedPreferences) {
    actual fun getItem(key: String): String? {
        return preferences.getString(key, null)
    }

    actual fun saveItem(key: String, value: String) {
        with(preferences.edit()) {
            putString(key, value)
            commit()
        }
    }

    actual fun deleteItem(key: String) {
        with(preferences.edit()) {
            remove(key)
            commit()
        }
    }

    actual fun deleteAll() {
        with(preferences.edit()) {
            clear()
            commit()
        }
    }
}
