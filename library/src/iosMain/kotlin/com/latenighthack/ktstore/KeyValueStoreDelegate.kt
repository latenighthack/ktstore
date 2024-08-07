@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.latenighthack.ktstore

import platform.Foundation.NSUserDefaults

actual class KeyValueStoreDelegate {
    actual fun getItem(key: String): String? {
        return NSUserDefaults.standardUserDefaults.stringForKey("cb.$key")
    }

    actual fun saveItem(key: String, value: String) {
        val defaults = NSUserDefaults.standardUserDefaults

        defaults.setObject(value, "cb.$key")
        defaults.synchronize()
    }

    actual fun deleteItem(key: String) {
        val defaults = NSUserDefaults.standardUserDefaults

        defaults.removeObjectForKey("cb.$key")
        defaults.synchronize()
    }

    actual fun deleteAll() {
        val defaults = NSUserDefaults.standardUserDefaults
        val defaultKeys = NSUserDefaults.standardUserDefaults
            .dictionaryRepresentation()
            .keys
            .mapNotNull {
                it as? String
            }

        for (key in defaultKeys) {
            if (key.startsWith("cb.")) {
                defaults.removeObjectForKey(key)
            }
        }
    }
}
