package com.latenighthack.ktstore

import org.postgresql.util.DriverInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verify the resolved JDBC artifact, rather than just the requested catalog version. */
class JdbcSecurityVersionTest {
    @Test fun resolvedDriverIncludesCurrentAuthenticationAndPaddingFixes() {
        assertEquals("42.7.14", DriverInfo.DRIVER_VERSION)
    }
}
