package com.latenighthack.ktstore

actual fun createStoreDelegate(db: String): StoreDelegate {
    return SqlStoreDelegate(SqliteDriver(db))
}
