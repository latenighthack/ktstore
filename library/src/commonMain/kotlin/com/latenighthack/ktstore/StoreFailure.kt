package com.latenighthack.ktstore

/** Messages deliberately contain no stored values. Cancellation is never wrapped. */
sealed class StoreFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class Closed : StoreFailure("Database connection is closed")
    class Unavailable(cause: Throwable? = null) : StoreFailure("Database is unavailable", cause)
    class Blocked(val operation: String) : StoreFailure("Database lifecycle operation is blocked")
    class Quota(cause: Throwable? = null) : StoreFailure("Storage quota exhausted", cause)
    class Constraint(cause: Throwable? = null) : StoreFailure("Storage constraint violated", cause)
    class Busy(cause: Throwable? = null) : StoreFailure("Database is busy", cause)
    class Timeout : StoreFailure("Database operation timed out; outcome may be unknown")
    class Aborted(cause: Throwable? = null) : StoreFailure("Transaction aborted", cause)
    class Migration(cause: Throwable? = null) : StoreFailure("Database migration failed", cause)
    class InvalidUsage(message: String = "Unsupported database operation") : StoreFailure(message)
    class CorruptRecord(cause: Throwable? = null) : StoreFailure("Stored record cannot be decoded", cause)
}

internal expect fun mapStorageFailure(error: Throwable): Throwable
