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

    @Test fun payloadsShareDecodesAcrossFetchesButSeparateCodecs() {
        val cache = SharedReadCache()
        val payload = byteArrayOf(1, 2)
        val codec = Any()
        var decodes = 0
        val generation = cache.generation("users")
        cache.storeRows("users", generation, listOf(payload), 2)
        val value = cache.decode("users", generation, payload, codec, 2, { decodes++; Any() }, { 1 })
        assertSame(value, cache.decode("users", generation, payload.copyOf(), codec, 2, { decodes++; Any() }, { 1 }))
        assertEquals(1, decodes)
        assertContentEquals(payload, cache.data(1) as ByteArray)
        val otherView = cache.decode("users", generation, payload, Any(), 2, { "different view" }, { 1 })
        assertEquals("different view", otherView)
        assertSame(value, cache.decode("users", generation, payload, codec, 2, { error("already decoded") }, { 1 }))
    }

    @Test fun invalidationDuringDecodeCannotPublishTheOldObject() {
        val cache = SharedReadCache()
        val generation = cache.generation("users")
        cache.decode("users", generation, byteArrayOf(1), Any(), 2, {
            cache.beginWrite("users", listOf(1)); cache.endWrite("users")
            "old object"
        }, { 1 })
        assertSame(SharedReadCache.Lookup.Miss, cache.lookup("users", 1))
        cache.decode("users", generation, byteArrayOf(1), Any(), 2, { "old object" }, { 1 })
        assertSame(SharedReadCache.Lookup.Miss, cache.lookup("users", 1))
    }

    @Test fun unaccessedPayloadsAndAbsencesShareTheCapacityBound() {
        val cache = SharedReadCache()
        val generation = cache.generation("users")
        val codec = Any()
        val first = cache.decode("users", generation, byteArrayOf(1), codec, 2, { Any() }, { 1 })
        cache.storeRows("users", generation, listOf(byteArrayOf(2), byteArrayOf(3)), 2)
        assertSame(SharedReadCache.Lookup.Miss, cache.lookup("users", 1))
        val next = cache.decode("users", generation, byteArrayOf(1), codec, 2, { Any() }, { 1 })
        assertNotSame(first, next)
        cache.store("users", generation, mapOf(4 to null, 5 to null), 2)
        assertSame(SharedReadCache.Lookup.Miss, cache.lookup("users", 1))
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
