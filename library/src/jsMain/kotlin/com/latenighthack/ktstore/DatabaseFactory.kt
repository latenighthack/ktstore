package com.latenighthack.ktstore

actual fun createDatabase(configuration: DatabaseConfiguration, location: String?): Database = Database(configuration, IndexDB(configuration))

internal actual fun mapStorageFailure(error: Throwable): Throwable = error
