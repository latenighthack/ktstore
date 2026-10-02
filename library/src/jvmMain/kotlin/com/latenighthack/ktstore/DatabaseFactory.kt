package com.latenighthack.ktstore

actual fun createDatabase(configuration: DatabaseConfiguration, location: String?): Database = Database(configuration, SqlStoreDelegate(JdbcDriver(location ?: configuration.identity, "sqlite"), "BLOB", configuration))

internal actual fun mapStorageFailure(error: Throwable): Throwable = when {
    error is StoreFailure || error is kotlinx.coroutines.CancellationException -> error
    error is java.sql.SQLException -> when {
        error.errorCode == 5 || error.errorCode == 6 -> StoreFailure.Busy(error)
        error.errorCode == 19 || error.sqlState?.startsWith("23") == true -> StoreFailure.Constraint(error)
        error.errorCode == 13 -> StoreFailure.Quota(error)
        error.errorCode == 11 || error.errorCode == 26 -> StoreFailure.CorruptRecord(error)
        else -> StoreFailure.Unavailable(error)
    }
    else -> error
}
