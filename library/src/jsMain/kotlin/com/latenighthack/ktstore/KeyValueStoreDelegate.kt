package com.latenighthack.ktstore

import kotlinx.browser.window

actual class KeyValueStoreDelegate {
    actual fun getItem(key: String): String? {
        return window.localStorage.getItem("cb.$key")
    }

    actual fun saveItem(key: String, value: String) {
        window.localStorage.setItem("cb.$key", value)
    }

    actual fun deleteItem(key: String) {
        window.localStorage.removeItem("cb.$key")
    }

    actual fun deleteAll() {
        val keys = mutableListOf<String>()

        for (i in 0 until window.localStorage.length) {
            keys.add(window.localStorage.key(i)!!)
        }

        for (key in keys) {
            if (key.startsWith("cb.")) {
                window.localStorage.removeItem(key)
            }
        }
    }
}
