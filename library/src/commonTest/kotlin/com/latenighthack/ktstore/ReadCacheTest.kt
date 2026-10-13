package com.latenighthack.ktstore

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ReadCacheTest {
    private fun SharedReadCache.data(key: Any?) = (lookup("users", key) as? SharedReadCache.Lookup.Hit)?.data

    @Test fun openWritersBypassAndRejectOverlappingFetches() {
        val cache = SharedReadCache()
        val written = mutableSetOf<String>()
        val before = cache.generation("users")
        cache.joinWrite(written, "users", listOf(1))
        assertSame(SharedReadCache.Lookup.Bypass, cache.lookup("users", 1))
        cache.store("users", cache.generation("users"), mapOf(1 to "uncommitted"), 10)
        cache.endWrites(written)
        assertSame(SharedReadCache.Lookup.Miss, cache.lookup("users", 1))
        cache.store("users", before, mapOf(1 to "read before the write"), 10)
        assertSame(SharedReadCache.Lookup.Miss, cache.lookup("users", 1))
        cache.store("users", cache.generation("users"), mapOf(1 to "committed", 2 to null), 10)
        assertEquals("committed", cache.data(1))
        assertIs<SharedReadCache.Lookup.Hit>(cache.lookup("users", 2))
        assertNull(cache.data(2))
    }

    @Test fun writesInvalidateTheirKeysOrTheWholeTable() {
        val cache = SharedReadCache()
        cache.store("users", 0, mapOf(1 to "a", 2 to "b"), 10)
        cache.beginWrite("users", listOf(1)); cache.endWrite("users")
        assertSame(SharedReadCache.Lookup.Miss, cache.lookup("users", 1))
        assertEquals("b", cache.data(2))
        cache.beginWrite("users", null); cache.endWrite("users")
        assertSame(SharedReadCache.Lookup.Miss, cache.lookup("users", 2))
        cache.store("users", cache.generation("users"), mapOf(3 to "c"), 10)
        cache.beginExclusive()
        assertSame(SharedReadCache.Lookup.Bypass, cache.lookup("users", 3))
        cache.endExclusive()
        assertSame(SharedReadCache.Lookup.Miss, cache.lookup("users", 3))
    }

    @Test fun cancelledBatchLeaderStillCompletesMembers() = runTest {
        val batcher = ReadBatcher()
        val relation = { id: Int -> StoreRelation.Eq(StoreKey.IntegerKey("id").bind(id)) }
        val load: suspend (Map<Any?, ReadBatcher.Request>) -> Unit = { requests ->
            requests.forEach { (key, request) -> request.result.complete(ReadBatcher.Loaded(key, null, false)) }
        }
        // The leader suspends while waiting for members to join, then is cancelled before reading.
        val leader = launch(start = CoroutineStart.UNDISPATCHED) { batcher.load(1, relation(1), 10, load) }
        val member = async(start = CoroutineStart.UNDISPATCHED) { batcher.load(2, relation(2), 10, load) }
        leader.cancel()
        assertEquals(2, member.await().data)
        assertTrue(leader.isCancelled)
    }
}
