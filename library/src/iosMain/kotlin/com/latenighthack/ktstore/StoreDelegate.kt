package com.latenighthack.ktstore

var createDriver: ((db: String) -> SqlDriver)? = null

actual fun createStoreDelegate(db: String): StoreDelegate {
    return SqlStoreDelegate(createDriver!!(db))
}
