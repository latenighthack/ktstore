package com.latenighthack.ktstore

actual fun createStoreDelegate(db: String): StoreDelegate {
    return SqlStoreDelegate(JdbcDriver(db, "postgresql"), blobType = "BYTEA")
}
