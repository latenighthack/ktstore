package com.latenighthack.ktstore

actual fun createDatabase(configuration: DatabaseConfiguration, location: String?): Database = Database(configuration, SqlStoreDelegate(AndroidSqliteDriver(java.io.File(requireNotNull(location) { "An app-owned absolute location is required" }).also { require(it.isAbsolute) }, configuration.busyTimeoutMillis), "BLOB", configuration))

internal actual fun mapStorageFailure(error: Throwable): Throwable = when (error) {
    is StoreFailure, is kotlinx.coroutines.CancellationException -> error
    is android.database.sqlite.SQLiteConstraintException -> StoreFailure.Constraint(error)
    is android.database.sqlite.SQLiteDatabaseLockedException -> StoreFailure.Busy(error)
    is android.database.sqlite.SQLiteFullException -> StoreFailure.Quota(error)
    is android.database.sqlite.SQLiteDatabaseCorruptException -> StoreFailure.CorruptRecord(error)
    is android.database.sqlite.SQLiteException -> StoreFailure.Unavailable(error)
    else -> error
}
