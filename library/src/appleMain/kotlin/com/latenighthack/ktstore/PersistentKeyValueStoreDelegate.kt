@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.latenighthack.ktstore

import platform.Foundation.NSUserDefaults

actual class PersistentKeyValueStoreDelegate actual constructor(private val storeName: String): KeyValueStoreDelegate {
    actual override suspend fun getItem(key: String): String? {
        return NSUserDefaults.standardUserDefaults.stringForKey("$storeName.$key")
    }

    actual override suspend fun saveItem(key: String, value: String) {
        val defaults = NSUserDefaults.standardUserDefaults

        defaults.setObject(value, "$storeName.$key")
        defaults.synchronize()
    }

    actual override suspend fun deleteItem(key: String) {
        val defaults = NSUserDefaults.standardUserDefaults

        defaults.removeObjectForKey("$storeName.$key")
        defaults.synchronize()
    }

    actual override suspend fun deleteAll() {
        val defaults = NSUserDefaults.standardUserDefaults
        val defaultKeys = NSUserDefaults.standardUserDefaults
            .dictionaryRepresentation()
            .keys
            .mapNotNull {
                it as? String
            }

        for (key in defaultKeys) {
            if (key.startsWith("$storeName.")) {
                defaults.removeObjectForKey(key)
            }
        }
    }
}
