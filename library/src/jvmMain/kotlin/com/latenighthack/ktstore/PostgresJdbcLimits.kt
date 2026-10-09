package com.latenighthack.ktstore

import java.net.URLDecoder
import java.net.URLEncoder
import java.sql.Connection

/** Finite transport and server-side limits; stricter existing settings remain in effect. */
data class PostgresJdbcLimits(
    val connectTimeoutSeconds: Int = 10,
    val socketTimeoutSeconds: Int = 30,
    val cancelSignalTimeoutSeconds: Int = 5,
    val statementTimeoutMillis: Int = 15_000,
    val lockTimeoutMillis: Int = 5_000,
) {
    init {
        require(connectTimeoutSeconds in 1..2_147_484 && socketTimeoutSeconds in 1..2_147_484 && cancelSignalTimeoutSeconds in 1..2_147_484)
        require(lockTimeoutMillis > 0 && statementTimeoutMillis > lockTimeoutMillis)
        require(statementTimeoutMillis.toLong() < socketTimeoutSeconds.toLong() * 1000)
    }

    fun boundedUrl(url: String): String {
        require(url.startsWith("jdbc:postgresql:"))
        val parameters = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }.toMutableList()
        for ((name, maximum) in listOf("connectTimeout" to connectTimeoutSeconds, "socketTimeout" to socketTimeoutSeconds, "cancelSignalTimeout" to cancelSignalTimeoutSeconds)) {
            val old = parameters.filter { it.first == name }.map {
                val parsed = requireNotNull(it.second.toIntOrNull()) { "Invalid JDBC $name" }
                require(parsed >= 0) { "Invalid JDBC $name" }
                if (parsed == 0) maximum else minOf(parsed, maximum)
            }
            parameters.removeAll { it.first == name }
            parameters.add(name to (old.minOrNull() ?: maximum).toString())
        }
        return url.substringBefore('?') + "?" + parameters.joinToString("&") {
            URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8")
        }
    }

    // pg_settings exposes statement/lock timeout values in milliseconds, including when the
    // user's URL supplied units. Per-connection configuration never changes global server policy.
    val initializationSql: String get() = "SELECT set_config(name, LEAST(CASE WHEN setting::bigint > 0 THEN setting::bigint ELSE CASE WHEN name = 'lock_timeout' THEN $lockTimeoutMillis ELSE $statementTimeoutMillis END END, CASE WHEN name = 'lock_timeout' THEN $lockTimeoutMillis ELSE $statementTimeoutMillis END)::text, false) FROM pg_settings WHERE name IN ('statement_timeout', 'lock_timeout')"

    fun initialize(connection: Connection) {
        connection.createStatement().use { it.execute(initializationSql) }
    }
}
