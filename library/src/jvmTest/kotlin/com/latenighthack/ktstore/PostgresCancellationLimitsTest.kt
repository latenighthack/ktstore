package com.latenighthack.ktstore

import kotlinx.coroutines.*
import java.sql.DriverManager
import org.junit.Assume.assumeTrue
import kotlin.test.*

class PostgresCancellationLimitsTest {
    private fun url(): String {
        val base = System.getenv("KTSTORE_TEST_PG_URL")
        assumeTrue("Actual PostgreSQL verification requires KTSTORE_TEST_PG_URL", base != null)
        return requireNotNull(base)
    }

    @Test fun canceledLockWaitJoinsWithinItsFiniteSqlLimit(): Unit = runBlocking {
        val base = url()
        val name = "ktstore_cancel_${System.nanoTime()}"
        val address = base + (if ('?' in base) "&" else "?") + "ApplicationName=$name"
        val blocker = DriverManager.getConnection(base)
        val key = System.nanoTime()
        blocker.prepareStatement("SELECT pg_advisory_lock(?)").use { s -> s.setLong(1, key); s.execute() }
        val driver = JdbcDriver(address.removePrefix("jdbc:postgresql:"), "postgresql",
            PostgresJdbcLimits(socketTimeoutSeconds = 3, statementTimeoutMillis = 1000, lockTimeoutMillis = 250))
        val task = launch(Dispatchers.IO) {
            val statement = driver.selectAll("SELECT pg_advisory_lock(?)")
            try { statement.bindInt(0, key); statement.step() }
            finally { statement.finalize() }
        }
        try {
            withTimeout(5000) {
                while (true) {
                    val waiting = blocker.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock')").use { s ->
                        s.setString(1, name); s.executeQuery().use { r -> r.next(); r.getBoolean(1) }
                    }
                    if (waiting) break
                    delay(5)
                }
            }
            withTimeout(1200) { task.cancelAndJoin() }
        } finally { withContext(NonCancellable) {
            blocker.prepareStatement("SELECT pg_advisory_unlock(?)").use { s -> s.setLong(1, key); s.execute() }
            blocker.close(); task.cancelAndJoin(); driver.close()
        } }
    }

    @Test fun existingStricterServerTimeoutsArePreserved(): Unit = runBlocking {
        val base = url()
        val address = base + (if ('?' in base) "&" else "?") + "options=-c%20lock_timeout%3D75ms"
        val driver = JdbcDriver(address.removePrefix("jdbc:postgresql:"), "postgresql")
        try {
            val values = mutableMapOf<String, Long>()
            val query = driver.selectAll("SELECT name, setting::bigint FROM pg_settings WHERE name IN ('lock_timeout', 'statement_timeout')")
            try { while (query.step()) values[query.getText(0)] = query.getLong(1) }
            finally { query.finalize() }
            assertEquals(75L, values["lock_timeout"])
            assertEquals(15_000L, values["statement_timeout"])
        } finally { driver.close() }
    }

    @Test fun transportLimitsRetainCredentialsAndStricterParameters() {
        val bounded = PostgresJdbcLimits().boundedUrl("jdbc:postgresql://host/database?user=test&password=a%2Bb&socketTimeout=0&connectTimeout=2&cancelSignalTimeout=1")
        assertTrue("socketTimeout=30" in bounded)
        assertTrue("connectTimeout=2" in bounded)
        assertTrue("cancelSignalTimeout=1" in bounded)
        assertTrue("password=a%2Bb" in bounded)
        assertFailsWith<IllegalArgumentException> { PostgresJdbcLimits().boundedUrl("jdbc:postgresql://host/database?socketTimeout=invalid") }
    }
}
