package com.latenighthack.ktstore

actual fun createDatabase(configuration: DatabaseConfiguration, location: String?): Database = Database(configuration, SqlStoreDelegate(SqliteDriver(requireNotNull(location) { "An app-owned absolute location is required" }.also { require(it.startsWith("/")) }, configuration.busyTimeoutMillis), "BLOB", configuration))

internal actual fun mapStorageFailure(error: Throwable): Throwable = error
