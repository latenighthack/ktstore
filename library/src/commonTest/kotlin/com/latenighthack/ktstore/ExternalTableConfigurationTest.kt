package com.latenighthack.ktstore

import example.UsersV1
import kotlin.test.*

class ExternalTableConfigurationTest {
    @Test fun externalTablesMustBeCanonicalAndMustNotMaskOwnedOrReservedTables() {
        val config = definitionDatabaseConfiguration("external", listOf(UsersV1))
        for (name in listOf("bad;sql", "MixedCase", "ktstore_schema", "sqlite_sequence", "android_metadata", "x".repeat(64), UsersV1.storeName.value)) {
            assertFailsWith<IllegalArgumentException>(name) { config.copy(externalTables = setOf(name)) }
        }
        assertEquals(setOf("room_claim"), config.copy(externalTables = setOf("room_claim")).externalTables)
    }
    @Test fun configurationSnapshotCopiesExternalOwnershipPolicy() {
        val names = mutableSetOf("room_claim")
        val config = definitionDatabaseConfiguration("external", listOf(UsersV1)).copy(externalTables = names)
        val snapshot = config.snapshot()
        names += "unexpected"
        assertEquals(setOf("room_claim"), snapshot.externalTables)
    }
}
