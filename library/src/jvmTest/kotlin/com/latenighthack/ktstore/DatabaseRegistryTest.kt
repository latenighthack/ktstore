package com.latenighthack.ktstore

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DatabaseRegistryTest {
    @Suppress("UNCHECKED_CAST")
    private fun registry() = Database::class.java.getDeclaredField("handles").let {
        it.isAccessible = true
        it.get(null) as Map<String, Set<Database>>
    }
    private fun database(identity: String) = Database(DatabaseConfiguration(identity, 1, emptyList()), InMemoryStoreDelegate())
    @Test fun lastCloseReleasesIdentityAndClosedOpenCannotReregister() = runBlocking {
        val identity = "registry-${java.util.UUID.randomUUID()}"
        val a = database(identity); val b = database(identity)
        a.open(); b.open()
        a.close()
        assertEquals(setOf(b), registry()[identity])
        b.close()
        assertFalse(registry().containsKey(identity))
        assertFailsWith<StoreFailure.Closed> { a.open() }
        assertFalse(registry().containsKey(identity))
    }

    @Test fun failedPartialOpenRemainsOwnedUntilClose() = runBlocking {
        val identity = "registry-partial-${java.util.UUID.randomUUID()}"
        var closed = false
        val delegate = object : LifecycleStoreDelegate by InMemoryStoreDelegate() {
            override suspend fun createStores() { throw IllegalStateException("partial open") }
            override suspend fun close() { closed = true }
        }
        val db = Database(DatabaseConfiguration(identity, 1, emptyList()), delegate)
        assertFailsWith<IllegalStateException> { db.open() }
        assertEquals(setOf(db), registry()[identity])
        db.close()
        assertTrue(closed)
        assertFalse(registry().containsKey(identity))
    }
}
